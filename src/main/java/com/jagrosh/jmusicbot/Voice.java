package com.jagrosh.jmusicbot;

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import org.vosk.LogLevel;
import org.vosk.Recognizer;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.json.JSONObject;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public class Voice implements AudioReceiveHandler {
    private final Model model;
    private final Recognizer recognizer;
    private boolean listeningForCommand = false;

    public Voice() throws IOException {
        // Initialize Vosk
        LibVosk.setLogLevel(LogLevel.DEBUG);
        this.model = new Model("vosk-model-small-de-0.15"); // Adjust the model as needed
        this.recognizer = new Recognizer(model, 16000); // 16kHz required by Vosk
        recognizer.setWords(true);
        System.out.println("Started Vosk model");
    }

    @Override
    public boolean canReceiveUser() {
        return true; // Allows this handler to receive user audio
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        // Receive audio data from Discord
        byte[] voice = userAudio.getAudioData(1.0); // Getting 20ms audio
        processAudio(voice); // Process the audio data directly
    }

    private void processAudio(byte[] voiceData) {
        try {
            // Transcode audio from 48kHz stereo to 16kHz mono
            byte[] transcodedData = transcodeVoice(voiceData);

            // Feed audio data to Vosk
            if (recognizer.acceptWaveForm(transcodedData, transcodedData.length)) {
                String result = recognizer.getResult();
                System.out.println("Final result: " + result);
                handleCommand(result);
            } else {
                String partialResult = recognizer.getPartialResult();
                System.out.println("Partial result: " + partialResult);
            }
        } catch (Exception e) {
            System.err.println("Error processing audio: " + e.getMessage());
        }
    }

    private byte[] transcodeVoice(byte[] voice) throws IOException {
        // Define audio formats
        AudioFormat originalFormat = new AudioFormat(48000.0f, 16, 2, true, true);
        AudioFormat targetFormat = new AudioFormat(16000.0f, 16, 1, true, false);

        // Create an AudioInputStream to perform the transcoding
        ByteArrayInputStream bais = new ByteArrayInputStream(voice);
        AudioInputStream originalStream = new AudioInputStream(bais, originalFormat, voice.length / originalFormat.getFrameSize());
        AudioInputStream transcodedStream = AudioSystem.getAudioInputStream(targetFormat, originalStream);
        
        return transcodedStream.readAllBytes(); // Return the transcoded byte array
    }

    private void handleCommand(String resultJson) {
        JSONObject result = new JSONObject(resultJson);
        String text = result.optString("text");

        // Handle command recognition logic
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

    public void close() throws IOException {
        recognizer.close();
        model.close();
    }
}

