package com.jagrosh.jmusicbot;

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import org.vosk.LogLevel;
import org.vosk.Recognizer;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Voice implements AudioReceiveHandler {

    private final Model model;
    private final Recognizer recognizer;
    private final BlockingQueue<short[]> audioQueue = new LinkedBlockingQueue<>();
    private boolean listeningForCommand = false;

    public Voice() throws IOException {
        // Initialize Vosk
        LibVosk.setLogLevel(LogLevel.DEBUG);
        this.model = new Model("vosk-model-small-de-0.15"); // Path to Vosk model directory
        this.recognizer = new Recognizer(model, 16000); // Assumes 16kHz sample rate
    }

    @Override
    public boolean canReceiveUser() {
        return true; // Allows this handler to receive user audio
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        short[] pcmData = decodeAudio(userAudio.getAudioData(1.0));
        audioQueue.add(pcmData);
        processAudio();
    }

    private void processAudio() {
        while (!audioQueue.isEmpty()) {
            short[] pcmData = audioQueue.poll();
            byte[] byteBuffer = new byte[pcmData.length * 2]; // Each short is 2 bytes
            ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmData);
            
            if (recognizer.acceptWaveForm(byteBuffer, byteBuffer.length)) {
                String result = recognizer.getResult();
                handleCommand(result);
            } else {
                System.out.println(recognizer.getPartialResult());
            }
        }
    }

    private void handleCommand(String resultJson) {
        JSONObject result = new JSONObject(resultJson);
        String text = result.optString("text");

        if (!listeningForCommand) {
            if (text.toLowerCase().contains("loki")) {
                listeningForCommand = true;
                System.out.println("Activation word 'Loki' detected. Listening for command...");
            }
        } else {
            if (text.toLowerCase().startsWith("play")) {
                String songTitle = text.substring(5);
                System.out.println("Command received: play " + songTitle);
                listeningForCommand = false;
            } else {
                System.out.println("No valid command detected after activation. Listening reset.");
                listeningForCommand = false;
            }
        }
    }

    private short[] decodeAudio(byte[] audioData) {
        ByteBuffer bb = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN);
        short[] audioShorts = new short[audioData.length / 2];
        bb.asShortBuffer().get(audioShorts);
        return audioShorts;
    }

    public void close() throws IOException {
        recognizer.close();
        model.close();
    }
}
