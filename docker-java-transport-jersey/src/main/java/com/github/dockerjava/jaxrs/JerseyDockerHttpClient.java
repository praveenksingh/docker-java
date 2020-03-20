package com.github.dockerjava.jaxrs;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerHttpClient;
import com.github.dockerjava.core.SSLConfig;
import com.github.dockerjava.jaxrs.filter.ResponseStatusExceptionFilter;
import com.github.dockerjava.jaxrs.filter.SelectiveLoggingFilter;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.io.EmptyInputStream;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.ClientResponseFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

public final class JerseyDockerHttpClient implements DockerHttpClient {

    public static final class Factory {

        private DockerClientConfig dockerClientConfig = null;

        private Integer readTimeout = null;

        private Integer connectTimeout = null;

        private Integer maxTotalConnections = null;

        private Integer maxPerRouteConnections = null;

        private Integer connectionRequestTimeout = null;

        private ClientRequestFilter[] clientRequestFilters = null;

        private ClientResponseFilter[] clientResponseFilters = null;

        private RequestEntityProcessing requestEntityProcessing;

        public Factory dockerClientConfig(DockerClientConfig value) {
            this.dockerClientConfig = value;
            return this;
        }

        public Factory readTimeout(Integer value) {
            this.readTimeout = value;
            return this;
        }

        public Factory connectTimeout(Integer value) {
            this.connectTimeout = value;
            return this;
        }

        public Factory maxTotalConnections(Integer value) {
            this.maxTotalConnections = value;
            return this;
        }

        public Factory maxPerRouteConnections(Integer value) {
            this.maxPerRouteConnections = value;
            return this;
        }

        public Factory connectionRequestTimeout(Integer value) {
            this.connectionRequestTimeout = value;
            return this;
        }

        public Factory clientResponseFilters(ClientResponseFilter[] value) {
            this.clientResponseFilters = value;
            return this;
        }

        public Factory clientRequestFilters(ClientRequestFilter[] value) {
            this.clientRequestFilters = value;
            return this;
        }

        public Factory requestEntityProcessing(RequestEntityProcessing value) {
            this.requestEntityProcessing = value;
            return this;
        }

        public JerseyDockerHttpClient build() {
            return new JerseyDockerHttpClient(
                dockerClientConfig,
                maxTotalConnections,
                maxPerRouteConnections,
                connectionRequestTimeout,
                readTimeout,
                connectTimeout,
                clientRequestFilters,
                clientResponseFilters,
                requestEntityProcessing
            );
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(JerseyDockerHttpClient.class.getName());

    private final Client client;

    private final PoolingHttpClientConnectionManager connManager;

    private final URI originalUri;

    private JerseyDockerHttpClient(
        DockerClientConfig dockerClientConfig,
        Integer maxTotalConnections,
        Integer maxPerRouteConnections,
        Integer connectionRequestTimeout,
        Integer readTimeout,
        Integer connectTimeout,
        ClientRequestFilter[] clientRequestFilters,
        ClientResponseFilter[] clientResponseFilters,
        RequestEntityProcessing requestEntityProcessing
    ) {
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.connectorProvider(new ApacheConnectorProvider());
        clientConfig.property(CommonProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true);

        if (requestEntityProcessing != null) {
            clientConfig.property(ClientProperties.REQUEST_ENTITY_PROCESSING, requestEntityProcessing);
        }

        clientConfig.register(new ResponseStatusExceptionFilter(dockerClientConfig.getObjectMapper()));
        // clientConfig.register(JsonClientFilter.class);
        RequestConfig.Builder requestConfigBuilder = RequestConfig.custom();

        clientConfig.register(new JacksonJsonProvider(dockerClientConfig.getObjectMapper()));

        // logging may disabled via log level
        clientConfig.register(new SelectiveLoggingFilter(LOGGER, true));

        if (readTimeout != null) {
            requestConfigBuilder.setSocketTimeout(readTimeout);
            clientConfig.property(ClientProperties.READ_TIMEOUT, readTimeout);
        }

        if (connectTimeout != null) {
            requestConfigBuilder.setConnectTimeout(connectTimeout);
            clientConfig.property(ClientProperties.CONNECT_TIMEOUT, connectTimeout);
        }

        if (clientResponseFilters != null) {
            for (ClientResponseFilter clientResponseFilter : clientResponseFilters) {
                if (clientResponseFilter != null) {
                    clientConfig.register(clientResponseFilter);
                }
            }
        }

        if (clientRequestFilters != null) {
            for (ClientRequestFilter clientRequestFilter : clientRequestFilters) {
                if (clientRequestFilter != null) {
                    clientConfig.register(clientRequestFilter);
                }
            }
        }

        URI originalUri = dockerClientConfig.getDockerHost();

        SSLContext sslContext = null;

        try {
            final SSLConfig sslConfig = dockerClientConfig.getSSLConfig();
            if (sslConfig != null) {
                sslContext = sslConfig.getSSLContext();
            }
        } catch (Exception ex) {
            throw new DockerClientException("Error in SSL Configuration", ex);
        }

        final String protocol = sslContext != null ? "https" : "http";

        switch (originalUri.getScheme()) {
            case "unix":
                break;
            case "tcp":
                try {
                    originalUri = new URI(originalUri.toString().replaceFirst("tcp", protocol));
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }

                configureProxy(clientConfig, originalUri, protocol);
                break;
            default:
                throw new IllegalArgumentException("Unsupported protocol scheme: " + originalUri);
        }

        connManager = new PoolingHttpClientConnectionManager(getSchemeRegistry(originalUri, sslContext)) {

            @Override
            public void close() {
                super.shutdown();
            }

            @Override
            public void shutdown() {
                // Disable shutdown of the pool. This will be done later, when this factory is closed
                // This is a workaround for finalize method on jerseys ClientRuntime which
                // closes the client and shuts down the connection pool when it is garbage collected
            }
        };

        if (maxTotalConnections != null) {
            connManager.setMaxTotal(maxTotalConnections);
        }
        if (maxPerRouteConnections != null) {
            connManager.setDefaultMaxPerRoute(maxPerRouteConnections);
        }

        clientConfig.property(ApacheClientProperties.CONNECTION_MANAGER, connManager);

        // Configure connection pool timeout
        if (connectionRequestTimeout != null) {
            requestConfigBuilder.setConnectionRequestTimeout(connectionRequestTimeout);
        }
        clientConfig.property(ApacheClientProperties.REQUEST_CONFIG, requestConfigBuilder.build());
        ClientBuilder clientBuilder = ClientBuilder.newBuilder().withConfig(clientConfig);

        if (sslContext != null) {
            clientBuilder.sslContext(sslContext);
        }

        client = clientBuilder.build();

        this.originalUri = originalUri;
    }

    private URI sanitizeUrl(URI originalUri) {
        if (originalUri.getScheme().equals("unix")) {
            return UnixConnectionSocketFactory.sanitizeUri(originalUri);
        }
        return originalUri;
    }

    private Registry<ConnectionSocketFactory> getSchemeRegistry(URI originalUri, SSLContext sslContext) {
        RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.create();
        registryBuilder.register("http", PlainConnectionSocketFactory.getSocketFactory());
        if (sslContext != null) {
            registryBuilder.register("https", new SSLConnectionSocketFactory(sslContext));
        }
        registryBuilder.register("unix", new UnixConnectionSocketFactory(originalUri));
        return registryBuilder.build();
    }

    @Override
    public Response execute(Request request) {
        if (request.hijackedInput() != null) {
            throw new UnsupportedOperationException("Does not support hijacking");
        }
        String url = sanitizeUrl(originalUri).toString();
        if (url.endsWith("/") && request.path().startsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        Invocation.Builder builder = client.target(url + request.path()).request();

        request.headers().forEach(builder::header);

        try {
            return new JerseyResponse(
                builder.build(request.method(), toEntity(request)).invoke()
            );
        } catch (ProcessingException e) {
            if (e.getCause() instanceof DockerException) {
                throw (DockerException) e.getCause();
            }
            throw e;
        }
    }

    private Entity<InputStream> toEntity(Request request) {
        InputStream body = request.body();
        if (body != null) {
            return Entity.entity(body, MediaType.APPLICATION_JSON_TYPE);
        }
        switch (request.method()) {
            case "POST":
                return Entity.json(null);
            default:
                return null;
        }
    }

    private void configureProxy(ClientConfig clientConfig, URI originalUri, String protocol) {
        List<Proxy> proxies = ProxySelector.getDefault().select(originalUri);

        for (Proxy proxy : proxies) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            if (address != null) {
                String hostname = address.getHostName();
                int port = address.getPort();

                clientConfig.property(ClientProperties.PROXY_URI, "http://" + hostname + ":" + port);

                String httpProxyUser = System.getProperty(protocol + ".proxyUser");
                if (httpProxyUser != null) {
                    clientConfig.property(ClientProperties.PROXY_USERNAME, httpProxyUser);
                    String httpProxyPassword = System.getProperty(protocol + ".proxyPassword");
                    if (httpProxyPassword != null) {
                        clientConfig.property(ClientProperties.PROXY_PASSWORD, httpProxyPassword);
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }

        if (connManager != null) {
            connManager.close();
        }
    }

    private static class JerseyResponse implements Response {

        private final javax.ws.rs.core.Response response;

        public JerseyResponse(javax.ws.rs.core.Response response) {
            this.response = response;
        }

        @Override
        public int getStatusCode() {
            return response.getStatus();
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return response.getStringHeaders();
        }

        @Override
        public InputStream getBody() {
            return response.hasEntity()
                ? response.readEntity(InputStream.class)
                : EmptyInputStream.INSTANCE;
        }

        @Override
        public void close() {
            response.close();
        }
    }
}
