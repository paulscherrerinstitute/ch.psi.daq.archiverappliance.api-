package ch.psi.daq.archiverappliance.api;

import ch.psi.daq.archiverappliance.api.api.v1.config.ChannelConfiguration;
import ch.psi.daq.archiverappliance.api.data.ArchiverChannelConfiguration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Service
public class ArchiverConfigurationManager {

    private static final Logger logger = LoggerFactory.getLogger(ArchiverConfigurationManager.class);

    private String serverName;
    private String channelsFileName = "channels.json";

    private Flux<String> channelsFlux;

    private ObjectMapper mapper;
    private WebClient webClient;

    public ArchiverConfigurationManager(@Value("server.name") String serverName, ObjectMapper mapper){
        this.serverName = serverName;
        this.mapper = mapper;

        // This documentation might be useful:
        // https://projectreactor.io/docs/netty/release/reference/index.html#_connection_pool
//        ConnectionProvider provider = ConnectionProvider.builder("fixed")
//                .maxConnections(1000)
//                .maxLifeTime(Duration.ofSeconds(10))
//                .build();

        TcpClient tcpClient = TcpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 120000)
                .doOnConnected(connection -> connection.addHandlerLast(new ReadTimeoutHandler(120000)))
                .doOnConnected(connection -> connection.addHandlerLast(new WriteTimeoutHandler(120000)));

        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(HttpClient.from(tcpClient));

        this.webClient = WebClient.builder()
                .clientConnector(connector)
                .build();

    }

    public Flux<String> getChannels(boolean reload){
        if(reload || channelsFlux == null) {
            try {
                List<String> channels = mapper.readValue(Paths.get(channelsFileName).toFile(), new TypeReference<>(){});
                channelsFlux = Flux.fromStream(channels.stream());
            } catch (IOException e) {
                logger.error("Unable to read channel file");
                Flux.fromStream(new ArrayList<String>().stream());
            }
//            return getChannelsFromArchiver();
        }
        return channelsFlux;
    }

    public Flux<String> getChannelsFromArchiver() {
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(-1))
                .build();

        return WebClient.builder()
                .exchangeStrategies(exchangeStrategies) // we need to increase the buffer size for the archiver request
                .build()
                .get()
                .uri("http://"+serverName+":17665/mgmt/bpl/getAllPVs?pv=*&limit=-1")
//                    .accept(MediaType.APPLICATION_JSON)
//                    .acceptCharset(Charset.forName("ISO-8859-1"))
                .retrieve()
//                    .bodyToMono(ChannelList.class)  // TODO This does not work for some reasons (Content type 'application/json;charset=ISO-8859-1' not supported)
                .bodyToMono(String.class)
                .map(v -> {
                    try {
                        List<String> list = mapper.readValue(v, new TypeReference<>(){});
                        return list;
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .flux()
                .flatMap(l -> Flux.fromIterable(l))
                .cache();
    }

    public Flux<ChannelConfiguration> getChannelConfigurations(String regexPattern){
        Predicate<String> channelFilter = x -> true;
        if(regexPattern != ".*"){
            channelFilter = x -> x.matches(regexPattern);
        }

        return getChannels(false)
                .filter(channelFilter)
                .delayElements(Duration.ofMillis(1))
                .flatMap(channel -> getChannelConfiguration(channel))
                .limitRequest(100) // TODO remove !!!!
                .filter(m -> m != null)
                .map(config -> mapArchiverChannelConfigurationToChannelConfiguration(config));
    }

    /**
     * Get the configuration of a single archiver channel
     * @param channelName
     * @return  Configuration of the archiver channel, null if configuration cannot be retrieved from the server
     */
    public Mono<ArchiverChannelConfiguration> getChannelConfiguration(String channelName){
        Mono<ArchiverChannelConfiguration> configuration = null;

        try {
            configuration = webClient
                    .get()
                    .uri("http://"+serverName+":17665/mgmt/bpl/getPVTypeInfo?pv={pv}", channelName)
                    .retrieve()
//                    .bodyToMono(ArchiverChannelConfiguration.class)
                    .bodyToMono(String.class)
                    .map(v -> {
                        try {
                            return mapper.readValue(v, ArchiverChannelConfiguration.class);
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                        return null;
                    });

//            configuration = webClient
//                    .get()
//                    .uri("http://"+serverName+":17665/mgmt/bpl/getPVTypeInfo?pv={pv}", channelName)
//                    .exchange()
//                    .flatMap(response -> response.bodyToMono(ByteArrayResource.class))
//                    .map(byteArrayResource -> {
//                        try (final InputStreamReader reader = new InputStreamReader(byteArrayResource.getInputStream(), StandardCharsets.UTF_8)) {
//                            return mapper.readValue(reader, ArchiverChannelConfiguration.class);
//                        } catch (final Throwable thrw) {
//                            logger.warn("Could not parse ArchiverApplianceChannelConfiguration of '{}'.", channelName);
//                            // null is not allowed -> what is the recommended pattern
//                            return null;
//                        }
//                    });
        }
        catch(WebClientResponseException e){
            logger.warn("Unable to retrieve configuration for channel {} - {}: {}", channelName, e.getRawStatusCode(), e.getStatusText());
        }
        catch(Exception e){
            logger.warn("Unable to retrieve configuration for channel {}: {}", channelName, e.getMessage());
        }
        return configuration;
    }

    private ChannelConfiguration mapArchiverChannelConfigurationToChannelConfiguration(ArchiverChannelConfiguration config){
        ChannelConfiguration configuration = new ChannelConfiguration();
        configuration.setName(config.getName());
        configuration.setType(config.getType());
        configuration.setShape(config.getShape());
        configuration.setUnit(config.getUnit());
        configuration.setSource(config.getSource());
        return configuration;
    }

    public Mono<Boolean>  fetchChannelConfigurations(){
        return getChannelConfigurations(".*").collectList().map( channelList -> {
            try {
                mapper.writeValue(Paths.get("channel_configurations.json").toFile(), channelList);
            } catch (IOException e) {
                logger.error("Unable to write channels configuration file", e);
                return false;
            }
            return true;
        });
    }

    public Mono<Boolean> fetchChannels(){
        return getChannelsFromArchiver().collectList().map( channelList -> {
            try {
                mapper.writeValue(Paths.get(channelsFileName).toFile(), channelList);
            } catch (IOException e) {
                logger.error("Unable to write channels file", e);
                return false;
            }
            return true;
        });
    }
}
