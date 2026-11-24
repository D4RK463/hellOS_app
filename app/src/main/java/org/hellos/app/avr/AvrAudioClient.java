// SPDX-License-Identifier: GPL-3.0-or-later
package org.hellos.app.avr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and sets the sound mode of a Denon HEOS AVR over its UPnP service
 * {@code urn:schemas-denon-com:service:ACT:1} (actions {@code GetAudioConfig} and
 * {@code SetAudioConfig}). The original HEOS AVR has no Telnet control (port 23); this service is
 * what the HEOS app uses.
 *
 * <p>Plain Java without Android dependencies. Every call is a short-lived, blocking HTTP request;
 * call it off the main thread. Instances are not thread-safe.
 */
public final class AvrAudioClient {

    /** Port of the UPnP device description of HEOS devices. */
    public static final int UPNP_PORT = 60006;
    private static final String DESCRIPTION_PATH = "/upnp/desc/aios_device/aios_device.xml";
    private static final String DEFAULT_CONTROL_PATH = "/ACT/control";
    private static final String SERVICE = "urn:schemas-denon-com:service:ACT:1";
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 4000;
    /** Responses are small; anything bigger is not a valid answer. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private static final Pattern ACT_CONTROL_URL = Pattern.compile(
            "<serviceType>\\s*" + Pattern.quote(SERVICE) + "\\s*</serviceType>.*?<controlURL>\\s*([^<\\s]+)\\s*</controlURL>",
            Pattern.DOTALL);
    private static final Pattern AUDIO_CONFIG_ARG = Pattern.compile(
            "<AudioConfig>(.*)</AudioConfig>", Pattern.DOTALL);
    private static final Pattern SOUND_MODE = Pattern.compile("<soundMode>([^<]*)</soundMode>");
    private static final Pattern AVAILABLE_MODES = Pattern.compile("<availableSoundModes>([^<]*)</availableSoundModes>");
    private static final Pattern FAULT = Pattern.compile("<(?:\\w+:)?Fault>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAULT_DETAIL = Pattern.compile(
            "<errorDescription>([^<]*)</errorDescription>|<faultstring>([^<]*)</faultstring>");

    private final String host;
    private String controlPath;

    /** @param host IP address or host name of the AVR */
    public AvrAudioClient(String host) {
        this.host = Objects.requireNonNull(host, "host").trim();
        if (this.host.isEmpty()) {
            throw new IllegalArgumentException("host is empty");
        }
    }

    public String host() {
        return host;
    }

    /** Reads the current audio configuration. */
    public AudioConfig audioConfig() throws IOException {
        String response = soap("GetAudioConfig", "");
        Matcher arg = AUDIO_CONFIG_ARG.matcher(response);
        if (!arg.find()) {
            throw new AvrException("No AudioConfig in the response");
        }
        // the argument is an XML document sent as escaped text; some firmwares send it unescaped
        String document = unescape(arg.group(1)).trim();
        return parse(document);
    }

    /**
     * Sets the sound mode. Reads the current configuration first and sends it back with only the
     * sound mode changed, so no other setting (speakers, crossover, ...) is touched.
     *
     * @return the configuration as reported by the device afterwards
     */
    public AudioConfig setSoundMode(String mode) throws IOException {
        Objects.requireNonNull(mode, "mode");
        if (!mode.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid sound mode: " + mode);
        }
        AudioConfig current = audioConfig();
        if (!SOUND_MODE.matcher(current.document()).find()) {
            throw new AvrException("The device does not report a sound mode");
        }
        String changed = SOUND_MODE.matcher(current.document())
                .replaceFirst(Matcher.quoteReplacement("<soundMode>" + mode + "</soundMode>"));
        soap("SetAudioConfig", "<AudioConfig>" + escape(changed) + "</AudioConfig>");
        return audioConfig();
    }

    static AudioConfig parse(String document) {
        Matcher mode = SOUND_MODE.matcher(document);
        String soundMode = mode.find() ? mode.group(1).trim() : "";
        List<String> available = new ArrayList<>();
        Matcher modes = AVAILABLE_MODES.matcher(document);
        if (modes.find()) {
            for (String part : modes.group(1).split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !available.contains(trimmed)) {
                    available.add(trimmed);
                }
            }
        }
        return new AudioConfig(soundMode, available, document);
    }

    // ---- SOAP ----------------------------------------------------------------------------------

    private String soap(String action, String arguments) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + SERVICE + "\">" + arguments + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        HttpURLConnection http = open(controlPath());
        try {
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            http.setRequestProperty("SOAPACTION", "\"" + SERVICE + "#" + action + "\"");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            http.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = http.getOutputStream()) {
                out.write(bytes);
            }
            int status = http.getResponseCode();
            String response = read(status >= 400 ? http.getErrorStream() : http.getInputStream());
            if (status >= 400 || FAULT.matcher(response).find()) {
                throw new AvrException(action + " failed: " + faultText(status, response));
            }
            return response;
        } finally {
            http.disconnect();
        }
    }

    /** The control URL of the ACT service, read once from the device description. */
    private String controlPath() throws IOException {
        if (controlPath == null) {
            String path = DEFAULT_CONTROL_PATH;
            HttpURLConnection http = open(DESCRIPTION_PATH);
            try {
                if (http.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    Matcher matcher = ACT_CONTROL_URL.matcher(read(http.getInputStream()));
                    if (matcher.find()) {
                        String url = matcher.group(1);
                        // usually a path; an absolute URL is reduced to its path, the host stays ours
                        path = url.startsWith("http") ? URI.create(url).getRawPath() : url;
                        if (!path.startsWith("/")) {
                            path = "/" + path;
                        }
                    }
                }
            } finally {
                http.disconnect();
            }
            controlPath = path;
        }
        return controlPath;
    }

    private HttpURLConnection open(String path) throws IOException {
        String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        HttpURLConnection http = (HttpURLConnection) URI.create("http://" + authority + ":" + UPNP_PORT + path)
                .toURL().openConnection();
        http.setConnectTimeout(CONNECT_TIMEOUT_MS);
        http.setReadTimeout(READ_TIMEOUT_MS);
        http.setUseCaches(false);
        http.setInstanceFollowRedirects(false);
        return http;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_RESPONSE_BYTES) {
                    throw new AvrException("Response too large");
                }
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String faultText(int status, String response) {
        Matcher detail = FAULT_DETAIL.matcher(response);
        if (detail.find()) {
            String text = detail.group(1) != null ? detail.group(1) : detail.group(2);
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return "HTTP " + status;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
