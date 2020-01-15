package io.graversen.minecraft.rcon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MinecraftClient implements IMinecraftClient {
    private static Logger LOG = LoggerFactory.getLogger(MinecraftClient.class);
    private static final int DEFAULT_PORT = 25575;
    private static final int RCON_AUTHENTICATION_FAILURE = -1;
    private static final int RCON_COMMAND = 2;
    private static final int RCON_AUTHENTICATION = 3;

    private final String connectionTuple;
    private final SocketChannel rconSocketChannel;
    private final AtomicInteger currentRequestCounter;
    private final ExecutorService executorService;

    private MinecraftClient(SocketChannel rconSocketChannel, String hostname, int port) {
        this.connectionTuple = String.format("%s:%d", hostname, port);
        this.rconSocketChannel = rconSocketChannel;
        this.currentRequestCounter = new AtomicInteger(1);
        this.executorService = Executors.newSingleThreadExecutor();
        LOG.info("Initialized with connection tuple '{}'", connectionTuple);
    }

    private Future<RconResponse> authenticateClient(String password) {
        LOG.info("Authenticating...");
        return sendRaw(RCON_AUTHENTICATION, password, true);
    }

    public static MinecraftClient connect(String hostname, String password) {
        return MinecraftClient.connect(hostname, password, MinecraftClient.DEFAULT_PORT);
    }

    public static MinecraftClient connect(String hostname, String password, int port) {
        try {
            final SocketChannel socketChannel = SocketChannel.open(new InetSocketAddress(hostname, port));
            final MinecraftClient rconClient = new MinecraftClient(socketChannel, hostname, port);

            final Future<RconResponse> authenticateResponse = rconClient.authenticateClient(password);
            final RconResponse rconResponse = authenticateResponse.get(5000, TimeUnit.MILLISECONDS);

            return rconClient;
        } catch (IOException | InterruptedException | ExecutionException e) {
            throw new RconConnectException(
                    e, "Connection to %s:%d failed: %s", hostname, port, e.getCause() != null ? e.getCause().getMessage() : e.getMessage()
            );
        } catch (TimeoutException e) {
            throw new RconConnectException(e, "Connection to %s:%d timed out", hostname, port);
        }
    }

    @Override
    public Future<RconResponse> sendRaw(String command) {
        return sendRaw(RCON_COMMAND, command, false);
    }

    private Future<RconResponse> sendRaw(int requestType, String command, boolean silently) {
        return executorService.submit(doSynchronousSend(requestType, command, silently));
    }

    private Callable<RconResponse> doSynchronousSend(int requestType, String command, boolean silently) {
        return () -> {
            if (!silently) printCommand(command);

            final long requestStart = System.currentTimeMillis();

            final int requestId = currentRequestCounter.getAndIncrement();

            final ByteBuffer bytesToSend = createRconByteBuffer(requestId, requestType, command);
            rconSocketChannel.write(bytesToSend);

            final String responseFromRcon = readResponse();
            byte b = responseFromRcon.getBytes()[0];

            final long requestEnd = System.currentTimeMillis();
            return new RconResponse(requestStart, requestEnd, requestId, b, responseFromRcon);
        };
    }

    private String readResponse() {
        final int byteSize = readData(Integer.BYTES).getInt();
        final ByteBuffer dataBytes = readData(byteSize - (2 * Byte.BYTES));
        final ByteBuffer packageTailBytes = readData(2 * Byte.BYTES);

        final int responseId = dataBytes.getInt();

        if (responseId == RCON_AUTHENTICATION_FAILURE) {
            throw new AuthenticationException();
        }

        final byte byteOne = packageTailBytes.get(0);
        final byte byteTwo = packageTailBytes.get(1);

        if (byteOne != 0 || byteTwo != 0) {
            throw new RuntimeException("Expected two nil bytes at the end of response data");
        }

        final byte[] dataBytesArray = new byte[dataBytes.remaining()];
        dataBytes.get(dataBytesArray);
        return new String(dataBytesArray);
    }

    private ByteBuffer readData(int bytes) {
        try {
            final ByteBuffer buffer = ByteBuffer.allocate(bytes);
            final int readCount = rconSocketChannel.read(buffer);

            if (readCount != bytes) {
                throw new RuntimeException(String.format("Expected %d bytes but received %d", bytes, readCount));
            }

            buffer.position(0);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            return buffer;
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to read %d bytes", bytes), e);
        }
    }

    private ByteBuffer createRconByteBuffer(int requestCount, int requestType, String command) {
        // In accordance with the RCON format: Length + Request ID + Type + Payload + Two nil bytes
        ByteBuffer byteBuffer = ByteBuffer.allocate((3 * Integer.BYTES) + command.length() + (2 * Byte.BYTES));
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

        byteBuffer.putInt((2 * Integer.BYTES) + command.length() + (2 * Byte.BYTES));
        byteBuffer.putInt(requestCount);
        byteBuffer.putInt(requestType);
        byteBuffer.put(command.getBytes());
        byteBuffer.put((byte) 0);
        byteBuffer.put((byte) 0);

        byteBuffer.position(0);

        return byteBuffer;
    }

    @Override
    public void close() throws IOException {
        executorService.shutdown();
        rconSocketChannel.close();
    }

    private void printCommand(String rawCommand) {
        LOG.info("Sending command: {}", rawCommand);
    }
}