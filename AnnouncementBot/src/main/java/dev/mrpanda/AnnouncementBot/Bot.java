package dev.mrpanda.AnnouncementBot;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.philippheuer.events4j.simple.SimpleEventHandler;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.events.ChannelGoLiveEvent;
import com.github.twitch4j.events.ChannelGoOfflineEvent;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.SearchListResponse;
import com.google.api.services.youtube.model.SearchResult;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.ChannelType;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.priv.PrivateMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import okhttp3.OkHttpClient;

public class Bot extends ListenerAdapter {
	
	public static String TWITCH_NICKNAME = ""; // Name of Twitch channel to be tracked
	public static String YOUTUBE_CHANNEL_ID = ""; // ID of the YouTube channel to be tracked
	public static String DISCORD_ID_OF_BOT_OWNER = ""; // Discord ID of the bot owner for controlling shutdown and register process
	
	public static String YOUTUBE_APP_NAME = ""; // Name of the Google Cloud app
	public static String DISCORD_TOKEN = ""; // Discord token
	public static String YOUTUBE_TOKEN = ""; // YouTube token
	public static String TWITCH_CLIENT_ID = ""; // Twitch client ID
	public static String TWITCH_CLIENT_SECRET = ""; // Twitch client secret
    
	public static void main(String[] args) {
		log("System", "Starting bot...");
		TwitchInit();
		YouTubeInit();
		DiscordInit();
		log("System", "Started!");
	}
	
	public static JDA jda = null;
	public static MessageChannel botChannel = null;
	public static YouTube youtube = null;
	public static TwitchClient twitch = null;
	public static Timer t = null;
	
	private final static Cache<String, Boolean> recentlyOffline = Caffeine.newBuilder().expireAfterWrite(15, TimeUnit.MINUTES).build();
	
	public static void TwitchInit() {
		log("Twitch", "Initializing Twitch client...");
		twitch = TwitchClientBuilder.builder().withClientId(TWITCH_CLIENT_ID)
	                .withClientSecret(TWITCH_CLIENT_SECRET).withDefaultEventHandler(SimpleEventHandler.class).withEnableHelix(true).build();
		log("Twitch", "Initialized!");
		
		log("Twitch", "Enabling stream event listener...");
		twitch.getClientHelper().enableStreamEventListener(TWITCH_NICKNAME);
		log("Twitch", "Done! Listening the channel " + TWITCH_NICKNAME + ".");

		twitch.getEventManager().getEventHandler(SimpleEventHandler.class).onEvent(ChannelGoLiveEvent.class, event -> {
			if(recentlyOffline.getIfPresent(event.getChannel().getId()) == null) {
				if(event.getStream().getType().equals("live")) {
					log("Twitch", "Channel went live.");
					if(botChannel != null) {
						log("Discord", "Sending an announcement...");
						botChannel.sendMessage("LIVE! " + ((TextChannel) botChannel).getGuild().getPublicRole().getAsMention()
							+ "\n\nhttps://www.twitch.tv/" + TWITCH_NICKNAME).queue();
						log("Discord", "Sent!");
					} else {
						log("Discord", "No registered channel found. Cannot send announcement.");
					}
				}
			} else {
				log("Twitch", "Channel went live, but it went offline recently. Ignored the event.");
			}
		});
		
		twitch.getEventManager().getEventHandler(SimpleEventHandler.class).onEvent(ChannelGoOfflineEvent.class, event -> {
			recentlyOffline.put(event.getChannel().getId(), true);
			log("Twitch", "Channel went offline.");
			log("System", "Added the channel ID to cache. Expires in 15 mins.");
		});
	}
	
	public static List<String> ids = new ArrayList<String>();
	
	public static void YouTubeInit() {
		if(youtube != null) {
			try {
				youtube.getRequestFactory().getTransport().shutdown();
			} catch (IOException e) {
				log("YouTube", "Error while refreshing client. Retrying...");
				YouTubeInit();
				return;
			}
			
			youtube = null;
		}
		
		log("YouTube", "Initializing YouTube client...");
		
		NetHttpTransport httpTransport = null;
		try {
			httpTransport = GoogleNetHttpTransport.newTrustedTransport();
		} catch (GeneralSecurityException | IOException e1) {
			log("YouTube", "Error while connecting to Google network. Retrying...");
			YouTubeInit();
			return;
		}
		
		youtube = new YouTube.Builder(httpTransport, JacksonFactory.getDefaultInstance(), null).setApplicationName(YOUTUBE_APP_NAME).build();
		log("YouTube", "Initialized!");
		
		log("YouTube", "Getting all the videos from the channel...");
		YouTube.Search.List request = null;
		try {
			request = youtube.search().list("snippet");
		} catch (IOException e) {
			log("YouTube", "Error while sending the search request. Retrying..");
			YouTubeInit();
			return;
		}
		
		SearchListResponse response = null;
		try {
			response = request.setKey(YOUTUBE_TOKEN).setChannelId(YOUTUBE_CHANNEL_ID).setOrder("date").setType("video").execute();
		} catch (IOException e) {
			log("YouTube", "Error while getting the search response. Retrying...");
			YouTubeInit();
			return;
		}
		
		log("YouTube", "Getting IDs of the videos...");
		List<SearchResult> search = response.getItems();
		for(SearchResult s : search) {
			ids.add(s.getId().getVideoId());
		}
		
		log("YouTube", "Done!");
		
		log("YouTube", "Enabling timer to check videos...");
		newVideoCheck();
	}
	
	public static void newVideoCheck() {
		t = new Timer();
		log("YouTube", "Enabled!");
		TimerTask tt = new TimerTask() {
			public void run() {
				log("YouTube", "Checking the channel...");
				YouTube.Search.List request = null;
				try {
					request = youtube.search().list("snippet");
				} catch (IOException e) {
					log("YouTube", "Error while sending the search request. Retrying in 15 mins.");
					return;
				}
				
				SearchListResponse response = null;
				try {
					response = request.setKey(YOUTUBE_TOKEN).setChannelId(YOUTUBE_CHANNEL_ID).setMaxResults(1L).setOrder("date").setType("video").execute();
				} catch (IOException e) {
					log("YouTube", "Error while getting the search response. Retrying in 15 mins.");
					return;
				}
				
				List<SearchResult> search = response.getItems();
				
				if(search.size() != 0)
					IDCheck(search.get(0));
			}
		};
		
		t.schedule(tt, 900000, 900000);
	}
	
	public static void IDCheck(SearchResult result) {
		if(result != null) {
			String id = result.getId().getVideoId();
			if(!ids.contains(id)) {
				log("YouTube", "New video found.");
				if(botChannel != null) {
					log("Discord", "Sending an announcement...");
					botChannel.sendMessage("New video on YouTube! " + ((TextChannel) botChannel).getGuild().getPublicRole().getAsMention()
							+ "\n\nhttps://www.youtube.com/watch?v=" + id).queue();
					log("Discord", "Sent!");
				} else {
					log("Discord", "No registered channel found. Cannot send announcement.");
				}
				log("YouTube", "Added the video ID to the list.");
				ids.add(id);
			} else {
				log("YouTube", "No new videos found.");
			}
		}
	}
	
	public static void DiscordInit() {
		log("Discord", "Initializing Discord client...");
		try {
			jda = JDABuilder.createDefault(DISCORD_TOKEN).addEventListeners(new Bot()).setAutoReconnect(true).build().awaitReady();
		} catch (LoginException | InterruptedException e) {
			log("Discord", "Error while initializing client. Retrying...");
			DiscordInit();
			return;
		}
		log("Discord", "Initialized!");
	}
	
	public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
		if(event.getAuthor().isBot()) return;
		else if(event.isFromType(ChannelType.PRIVATE)) return;
		
		Message message = event.getMessage();
		String[] msgRaw = message.getContentDisplay().split(" ");
		String[] msg = Arrays.stream(msgRaw).filter(value -> value != null && value.length() > 0).toArray(size -> new String[size]);
		
		if(msg[0].equals("!register") && event.getAuthor().getId().equals(DISCORD_ID_OF_BOT_OWNER)) {
			if(msg.length != 2) {
				event.getChannel().sendMessage("Usage: !register <channel tag>").queue();
				return;
			}
			
			botChannel = message.getMentionedChannels().get(0);
			event.getChannel().sendMessage("Registered **#" + botChannel.getName() + "** as the announcement channel.").queue();
			log("Discord", "Registered the channel #" + botChannel.getName() + " (" + event.getGuild().getName() + ").");
		} else if(msg[0].equals("!shutdown") && event.getAuthor().getId().equals(DISCORD_ID_OF_BOT_OWNER)) {
			shutdownBot(event.getChannel());
		}
	}
	
	public static Timer concert = null;
	public static List<Message> messages = new ArrayList<Message>();
	
	public void onPrivateMessageReceived(@Nonnull PrivateMessageReceivedEvent event) {
		if(event.getAuthor().isBot()) return;
		
		String[] msgRaw = event.getMessage().getContentDisplay().split(" ");
		String[] msg = Arrays.stream(msgRaw).filter(value -> value != null && value.length() > 0).toArray(size -> new String[size]);
		
		if(msg[0].equals("!shutdown") && event.getAuthor().getId().equals(DISCORD_ID_OF_BOT_OWNER)) {
			shutdownBot(event.getChannel());
		}
	}
	
	public static void shutdownBot(MessageChannel channel) {
		log("System", "Shutting the bot down...");
		channel.sendMessage("See you!").complete();
		if(t != null)
			t.cancel();
		if(concert != null)
			concert.cancel();
		log("System", "Timers closed.");
		twitch.getEventManager().close();
		twitch.close();
		log("Twitch", "Closed.");
		try {
			youtube.getRequestFactory().getTransport().shutdown();
		} catch (IOException e) {
			log("YouTube", "Error while closing client. Force closing.");
		}
		log("YouTube", "Closed.");
		jda.shutdownNow();
		OkHttpClient client = jda.getHttpClient();
		client.connectionPool().evictAll();
		client.dispatcher().executorService().shutdown();
		log("Discord", "Closed.");
		log("System", "Closed.");
		System.exit(0);
	}
	
	public static void log(String source, String data) {
		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyy HH:mm:ss");
		if(source.length() == 7)
			System.out.println("[" + dtf.format(ZonedDateTime.now(ZoneId.of("GMT+3"))) + "][" + source + "] " + data);
		else
			System.out.println("[" + dtf.format(ZonedDateTime.now(ZoneId.of("GMT+3"))) + "] [" + source + "] " + data);
	}
}
