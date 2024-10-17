package com.jagrosh.jmusicbot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.PrivateChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.RestAction;

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
import java.util.EnumSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

public class Voice implements AudioReceiveHandler {
    private final Model model;
    private final Recognizer recognizer;
    private Timer timeoutTimer;
    private final Guild guild; // The guild context
    private final Bot bot;

    public Voice(Guild guild, Bot bot) throws IOException {
        // Initialize Vosk
        LibVosk.setLogLevel(LogLevel.DEBUG);
        this.model = new Model("vosk-model");
        this.recognizer = new Recognizer(model, 16000); // 16kHz required by Vosk
        this.guild = guild; // The guild context
        this.bot = bot;
        recognizer.setWords(true);
        System.out.println("Started Vosk model");
    }

    @Override
    public boolean canReceiveUser() {
        return true;
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        // Receive audio data from Discord
        byte[] voice = userAudio.getAudioData(1.0);
        processAudio(voice);
    }

    private void processAudio(byte[] voiceData) {
        try {
            byte[] transcodedData = transcodeVoice(voiceData);

            if (recognizer.acceptWaveForm(transcodedData, transcodedData.length)) {
                String result = recognizer.getResult();
                System.out.println("Final result: " + result);
            } else {
                String partialResult = recognizer.getPartialResult();
                //System.out.println("Partial result: " + partialResult);
                resetTimeout();
            }
        } catch (Exception e) {
            System.err.println("Error processing audio: " + e.getMessage());
        }
    }

    private byte[] transcodeVoice(byte[] voice) throws IOException {
        // Define audio formats
        AudioFormat originalFormat = new AudioFormat(48000.0f, 16, 2, true, true);
        AudioFormat targetFormat = new AudioFormat(16000.0f, 16, 1, true, false);

        ByteArrayInputStream bais = new ByteArrayInputStream(voice);
        AudioInputStream originalStream = new AudioInputStream(bais, originalFormat, voice.length / originalFormat.getFrameSize());
        AudioInputStream transcodedStream = AudioSystem.getAudioInputStream(targetFormat, originalStream);
        
        return transcodedStream.readAllBytes();
    }

    private void handleCommand(String resultJson) {
        JSONObject result = new JSONObject(resultJson);
        String text = result.optString("text").toLowerCase();

        if (text.contains("loki")) {
            String commandPart = text.substring(text.indexOf("loki") + 5).trim();
            if (!commandPart.isEmpty()) {
                if (commandPart.startsWith("spiel")) {
                    String songTitle = commandPart.substring(5).trim();
                    playSong(songTitle);
                    System.out.println("Command received: play " + songTitle);
                } else if (commandPart.startsWith("weiter")) {
                	AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
                    if (handler != null && handler.getPlayer().getPlayingTrack() != null && handler.getPlayer().isPaused()) {
                        handler.getPlayer().setPaused(false);
                        System.out.println("Resumed playback.");
                    } else {
                        System.out.println("No track is currently paused.");
                    }
                	
                    System.out.println("Command received: weiter");
                } else if (commandPart.startsWith("pause")) {
                	AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
                    if (handler != null && handler.getPlayer().getPlayingTrack() != null && handler.getPlayer().isPaused()) {
                    	System.out.println("No track is currently playing.");
                    } else {
                    	handler.getPlayer().setPaused(true);
                        System.out.println("Paused playback.");
                    }
                	
                    System.out.println("Command received: pause");
                } else {
                    System.out.println("Unknown command: " + commandPart);
                }
            }
        }
    }

    // Start a timeout to check for final result after a period of silence
    private void startTimeout() {
        timeoutTimer = new Timer();
        timeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Call getFinalResult if no new audio data is received in a while
                String finalResult = recognizer.getFinalResult();
                System.out.println("Final result from timeout: " + finalResult);
                handleCommand(finalResult);
                stopTimeout(); // Stop the timer after use
            }
        }, 2000); // Adjust timeout duration as needed (3000ms = 3 seconds)
    }

    // Reset the timeout if new audio data is received
    private void resetTimeout() {
        stopTimeout();
        startTimeout();
    }

    // Stop the timeout timer
    private void stopTimeout() {
        if (timeoutTimer != null) {
            timeoutTimer.cancel();
            timeoutTimer = null;
        }
    }
    
    private void playSong(String songTitle) {
        User fakeUser = new FakeUser("1234567890", "Vosk"); // Create a fake user for the command
        bot.getPlayerManager().loadItemOrdered(guild, songTitle, new ResultHandler(false, guild, songTitle, fakeUser)); // Pass the fake user here
    }

    private class ResultHandler implements AudioLoadResultHandler {
        private final boolean ytsearch;
        private final Guild guild;
        private final String songTitle;
        private final User fakeUser;

        private ResultHandler(boolean ytsearch, Guild guild, String songTitle, User fakeUser) {
            this.ytsearch = ytsearch;
            this.guild = guild;
            this.songTitle = songTitle;
            this.fakeUser = fakeUser;
        }

        private void loadSingle(AudioTrack track) {
            if (bot.getConfig().isTooLong(track)) {
                return; // Skip if the track is too long
            }
            AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandlerCustom(track, fakeUser, songTitle))) + 1; // Use customEvent
            System.out.println("Track loaded: " + track.getInfo().title + " at position " + pos);
        }

        private int loadPlaylist(AudioPlaylist playlist, AudioTrack exclude) {
            int[] count = {0};
            playlist.getTracks().forEach(track -> {
                if (!bot.getConfig().isTooLong(track) && !track.equals(exclude)) {
                    AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
                    handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandlerCustom(track, fakeUser, songTitle))); // Use customEvent
                    count[0]++;
                }
            });
            return count[0];
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            loadSingle(track); // Load the single track
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            if (playlist.getTracks().size() == 1 || playlist.isSearchResult()) {
                AudioTrack single = playlist.getSelectedTrack() == null ? playlist.getTracks().get(0) : playlist.getSelectedTrack();
                loadSingle(single);
            } else if (playlist.getSelectedTrack() != null) {
                AudioTrack single = playlist.getSelectedTrack();
                loadSingle(single);
            } else {
                int count = loadPlaylist(playlist, null);
                if (playlist.getTracks().size() == 0) {
                    System.out.println("Playlist is empty.");
                } else if (count == 0) {
                    System.out.println("No tracks added to the queue from the playlist.");
                } else {
                    System.out.println(count + " tracks added to the queue from the playlist.");
                }
            }
        }

        @Override
        public void noMatches() {
            if (!ytsearch) {
                bot.getPlayerManager().loadItemOrdered(guild, "ytsearch:" + songTitle, new ResultHandler(true, guild, songTitle, fakeUser)); // Pass the fake user
            }
        }

        @Override
        public void loadFailed(FriendlyException throwable) {
            System.err.println("Loading failed: " + throwable.getMessage());
        }
    }

    
    private class FakeUser implements User {
        private final String id;
        private final String name;

        public FakeUser(String id, String name) {
            this.id = id;
            this.name = name;
        }

        // Implement necessary User methods
        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }

        // Implement other required methods from User interface
        @Override
        public String getAvatarId() {
            return null; // or return a default avatar ID
        }

        @Override
        public String getDiscriminator() {
            return "0000"; // example discriminator
        }

        @Override
        public boolean isBot() {
            return false; // or true if you want to simulate a bot
        }

        // Add other methods as needed...

        // Override equals() and hashCode() for proper functioning
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof User)) return false;
            User user = (User) obj;
            return id.equals(user.getId());
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

		@Override
		public String getAsMention() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public long getIdLong() {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public String getDefaultAvatarId() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public RestAction<Profile> retrieveProfile() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getAsTag() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public boolean hasPrivateChannel() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public RestAction<PrivateChannel> openPrivateChannel() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public List<Guild> getMutualGuilds() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public boolean isSystem() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public JDA getJDA() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public EnumSet<UserFlag> getFlags() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public int getFlagsRaw() {
			// TODO Auto-generated method stub
			return 0;
		}
    }
    public void close() throws IOException {
        recognizer.close();
        model.close();
    }
}

