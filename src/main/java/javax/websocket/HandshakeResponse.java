package javax.websocket;

import java.util.List;
import java.util.Map;

public interface HandshakeResponse {

    /**
     * Name of the WebSocket accept HTTP header.
     */
    String SEC_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept";

    Map<String,List<String>> getHeaders();
}
