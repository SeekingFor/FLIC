package plugins.FLIC.storage;

import java.util.Date;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import freenet.support.SimpleFieldSet;

import plugins.FLIC.Worker;
//import plugins.FLIC.freenetMagic.USK_IdentityFetcher;
//import plugins.FLIC.freenetMagic.USK_MessageFetcher;

public class RAMstore {
	public ConcurrentHashMap<String, User> userMap;
	public List<Channel> channelList;
	public List<String> knownIdents;
	private String announceKey="";
	private long announceEdition=-1;
	private List<Long> announceEditionFinished;
	private long lastFinishedAnnounceEdition = -1;
	private String currentDateString="";
	private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
	// TODO: create store for pubkeys of invalid messages.
	// TODO: allows automatic blocking of senders of invalid messages
	public int announce_valid = 0;
	public int announce_duplicate = 0;
	public int identity_valid = 0;
	public int message_valid = 0;
	public int announce_ddos = 0;
	public int identity_ddos = 0;
	public int message_ddos = 0;
	public Config config;
	public String welcomeText;
	private Worker mPtrWorker;

	//private List<String> statusList;
	
	public RAMstore() {
		//this.statusList = new ArrayList<String>(); TODO: wtf is this?
		this.userMap = new ConcurrentHashMap<String, RAMstore.User>();
		this.knownIdents = new ArrayList<String>();
		this.channelList = new ArrayList<RAMstore.Channel>();
		this.sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		this.currentDateString = getCurrentUtcDate();
		this.config = new Config();
		this.announceEditionFinished = new ArrayList<Long>();
		this.announceKey="KSK@" + this.config.messageBase + "|"+ this.currentDateString + "|Announce|" + announceEdition;
		this.welcomeText = "[b]Welcome to flircp.[/b]\n\n";
		this.welcomeText += "This plugin provides a realtime chat for Freenet.\n";
		this.welcomeText += "Latency is mostly 15 to 30 seconds per request/insert on a well connected node.\n";
		this.welcomeText += "flircp is compatible with FLIP so you can communicate with other users of flircp and FLIP.\n";
		this.welcomeText += "This software is currently in beta stage (" + config.version_major + "." + config.version_minor + "." + config.version_release + ").\n";
		this.welcomeText += "If you find a bug or have a feature proposal to make please tell me (SeekingFor) about it. You can reach me in #flircp.\n\n";
		this.welcomeText += "Kudos to somedude for writing [a]FLIP[/a] in the first place. Also thanks karl for his patches for FLIP and TheSeeker for helping me out with various issues I had.\n";
		this.welcomeText += "If you want to use an IRC client instead of this plugin you can check out the [a]Freenet Social Network Guide for FLIP[/a] written by JustusRanvier.\n\n";
		this.welcomeText += "As this is the first time you start flircp please take the time to configure your settings.\n\n";
		this.welcomeText += "Your initial announcement to other users may be a little slower than it would be possible. Next versions should improve this.\n";
		this.welcomeText += "For a list of commands type /help. If you have any questions feel free to head over to #flircp.";
	}
	
	public void setPtrWorker(Worker ptrWorker) {
		this.mPtrWorker = ptrWorker;
	}
	
	public class Config {
		// general
		public Boolean firstStart = false;
		public short concurrentAnnounceFetcher = 10;
		public short maxMessageRetriesAfterDNF = 10;
		public String messageBase = "flip";
		public int version_major = 0;
		public int version_minor = 0;
		public int version_release = 1;
		public Boolean AllowFullAccessOnly = true;
		public Config() {
			// TODO: load config from file / db / whatever
		}
	}
	
	public class Channel implements Comparable<Channel>{
		public String name;
		public String topic;
		public int currentUserCount;
		public long lastMessageActivity;
		public Channel(String channelName) {
			this.name = channelName;
			this.topic = "";
			this.currentUserCount = 0;
			this.lastMessageActivity = 0;
		}
		@Override
		public int compareTo(Channel o) {
			return this.name.compareTo(o.name);
		}
		
	}
	public Boolean addNewChannel(String channelName) {
		// don't allow:
		// - channels not starting with #
		// - channels with more than one #
		// - channels without #
		// - channels with empty name
		// - channels with size > 50
		if(channelName.startsWith("#")
				&& channelName.replace("#", "").length() == channelName.length() - 1
				&& !channelName.equals("#")
				&& channelName.length() < 51) {
			Boolean found = false;
			Iterator<Channel> iter = channelList.iterator();
			Channel chan;
			while(iter.hasNext()) {
				chan = iter.next();
				// FIXME: how the fuck can chan == null?
				// FIXME: also, use getChannel() instead?
				// FIXME: also, why the fuck is this not a hashmap?
				if(chan != null && chan.name.equals(channelName)) {
					found = true;
					break;
				}
			}
			if(!found) {
				channelList.add(new Channel(channelName));
				Collections.sort(channelList);
				SimpleFieldSet event = new SimpleFieldSet(true);
				event.putSingle("command", "gotChannelFound");
				event.putSingle("channel", channelName.substring(1, channelName.length()));
				event.putSingle("timestamp", Long.toString(new Date().getTime()));
				mPtrWorker.getFCPParser().addFLICevent(event);
				//addStatus("gotChannelFound\n--channelName=" + channelName + "\n--timestamp=" + new Date().getTime() + "\nEndMessage");
			}
		} else {
			return false;
		}
		return true;
	}
	public Channel getChannel(String channelName) {
		Iterator<Channel> iter = channelList.iterator();
		Channel chan;
		while(iter.hasNext()) {
			chan = iter.next();
			if(chan.name.equals(channelName)) {
				return chan;
			}
		}
		return null;
	}
	public void setTopic(String channelName, String newTopic) {
		if(getChannel(channelName) != null) {
			getChannel(channelName).topic = newTopic;
		}
	}
	public class User {
		// FIXME: change editions to long
		// TODO: clean this up and remove unnecessary variables.
		public String nick;
		public String originalNick;
		public String RSA;
		public long identEdition;
		public long messageEditionHint;
		public Boolean identityRequested;
		public List<String> channels;
		public Boolean updatedMessageIndexFromIndentityMessage;
		public int messageCount;
		public int channelCount;
		public long lastActivity;
		public long identity_ddos;
		public long message_ddos;
		public Boolean identSubscriptionActive;
		public List<String> failedMessageRequests;
		public long lastMessageTime;
		public String lastAnnounceFoundFor;
		public User() {
			this.nick = "";
			this.originalNick = "";
			this.RSA = "";
			this.identEdition = -1;
			this.messageEditionHint = -1;
			this.identityRequested = false;
			this.channels = new ArrayList<String>();
			this.updatedMessageIndexFromIndentityMessage = false;
			this.messageCount = 0;
			this.channelCount = 0;
			this.lastActivity = new Date().getTime();
			this.identity_ddos = 0;
			this.message_ddos = 0;
			this.identSubscriptionActive = false;
			this.failedMessageRequests = new ArrayList<String>();
			this.lastMessageTime = new Date().getTime();
		}
	}
	
	public void addNewUser(String requestKey) {
		addNewUser(requestKey, false);
	}
	public void addNewUser(String requestKey, boolean ownIdentity) {
		User newUser = new User();
		if(!ownIdentity) {
			newUser.lastAnnounceFoundFor = getCurrentDateString();
		} else {
			newUser.lastAnnounceFoundFor = "";
		}
		userMap.put(requestKey, newUser);
		knownIdents.add(requestKey);
		mPtrWorker.getIdentityFetcher().addInitialSubscription(requestKey);
	}
	public void addUserToChannel(String requestKey, String channel) {
		userMap.get(requestKey).lastActivity = new Date().getTime();
		if(!userMap.get(requestKey).channels.contains(channel)) {
			userMap.get(requestKey).channels.add(channel);
			userMap.get(requestKey).channelCount += 1;
			getChannel(channel).currentUserCount += 1;
			SimpleFieldSet event = new SimpleFieldSet(true);
			event.putSingle("command", "gotChannelJoin");
			event.putSingle("channel", channel.substring(1, channel.length()));
			event.putSingle("userKey", requestKey);
			event.putSingle("currentNick", userMap.get(requestKey).nick);
			event.putSingle("timestamp", Long.toString(new Date().getTime()));
			mPtrWorker.getFCPParser().addFLICevent(event);
			//addStatus("gotChannelJoin\n--channelName=" + channel + "\n--userKey=" + requestKey + "\n--timestamp=" + new Date().getTime() + "\nEndMessage");
		}
	}
	public Boolean isUserInChannel(String requestKey, String channel) {
		return userMap.get(requestKey).channels.contains(channel);
	}
	public void removeUserFromChannel(String requestKey, String channel) {
		userMap.get(requestKey).lastActivity = new Date().getTime();
		removeUserFromChannel(requestKey, channel, false);
	}
	public void removeUserFromChannel(String requestKey, String channel, Boolean timeout) {
		if(isUserInChannel(requestKey, channel)) {
			getChannel(channel).currentUserCount -= 1;
			userMap.get(requestKey).channelCount -= 1;
			userMap.get(requestKey).channels.remove(channel);
			SimpleFieldSet event = new SimpleFieldSet(true);
			event.putSingle("command", "gotChannelPart");
			event.putSingle("channel", channel.substring(1, channel.length()));
			event.putSingle("userKey", requestKey);
			event.putSingle("currentNick", userMap.get(requestKey).nick);
			event.putSingle("timedOut", timeout.toString());
			event.putSingle("timestamp", Long.toString(new Date().getTime()));
			mPtrWorker.getFCPParser().addFLICevent(event);
		}
	}
	public void updateLastMessageActivityForChannel(String channel) {
		getChannel(channel).lastMessageActivity = new Date().getTime();
	}
	public List<String> getUsersInChannel(String channel) {
		String ident;
		List<String> userList = new ArrayList<String>();
		Iterator<String> iter = knownIdents.iterator();
		while (iter.hasNext()) {
			ident = iter.next();
			if(isUserInChannel(ident, channel)) {
				userList.add(userMap.get(ident).nick);
			}
		}
		Collections.sort(userList);
		return userList;
	}
	
	public void checkUserActivity() {
		// 1000 ms * 60 seconds * 17 minutes
		long datetimeToKick = new Date().getTime() - 17 * 60 * 1000;
		String ident;
		List<String> toRemove = new ArrayList<String>();
		try {
			Iterator<String> iter = knownIdents.iterator();
			Iterator<String> chanIter;
			while (iter.hasNext()) {
				ident = iter.next();
				if(userMap.get(ident).lastActivity < datetimeToKick && userMap.get(ident).channelCount > 0) {
					chanIter = userMap.get(ident).channels.iterator();
					while(chanIter.hasNext()) {
						toRemove.add(chanIter.next());
					}
					for (String chan : toRemove) {
						removeUserFromChannel(ident, chan, true);
					}
					toRemove.clear();
				}
			}
		} catch (ConcurrentModificationException e) {
			// ignore. we can do it the next time
		}
	}
	public Boolean isNickInUseByOtherIdentity(String requestKey, String nick) {
		String ident;
		Iterator<String> iter = knownIdents.iterator();
		while(iter.hasNext()) {
			ident = iter.next();
			if(nick.equals(userMap.get(ident).nick)) {
				if(!requestKey.equals(ident)) {
					return true;
				}
			}
		}
		return false;
	}
	public String getWhoIs(String nickname) {
		// FIXME: REMOVE
		String ident;
		Iterator<String> iter = knownIdents.iterator();
		Iterator<String> chanIter;
		StringBuilder channels = new StringBuilder();
		while(iter.hasNext()) {
			ident = iter.next();
			if(userMap.get(ident).nick.equals(nickname)) {
				chanIter = userMap.get(ident).channels.iterator();
				while(chanIter.hasNext()) {
					channels.append(chanIter.next());
					channels.append(" ");
				}
				if(channels.length() > 0) {
					channels.deleteCharAt(channels.length() -1);
				}
				return "[" + nickname + "] Public key: " + ident + "\n[" + nickname + "] channels: " + channels.toString() + "\n[" + nickname + "] has been idle: " + ((new Date().getTime() - userMap.get(ident).lastMessageTime) / 1000) + " seconds" ;
			}
		}
		return "[WHOIS] nick not found.";
	}
	public Boolean isIdentityFound(String requestKey) {
		return userMap.get(requestKey).identityRequested;
	}
	public void setIdentityFound(String requestKey, Boolean found) {
		userMap.get(requestKey).identityRequested = found;
	}
	public void setAllEditionsToZero() {
		// FIXME: use two version, 5 min before UTC datechange to create new
		// FIXME: subscriptions and 5 min after UTC datechange to stop old subscriptions.
		setAnnounceEdition(-1);
		lastFinishedAnnounceEdition = -1;
		announceEditionFinished.clear();
		mPtrWorker.getFCPParser().setAllEditionsToZero();
		String ident;
		Iterator<String> iter = knownIdents.iterator();
		while (iter.hasNext()) {
			ident = iter.next();
			setIdentEdition(ident, 0);
			setMessageEditionHint(ident, 0);
		}
		mPtrWorker.getIdentityFetcher().resetSubcriptions();
		mPtrWorker.getMessageFetcher().resetSubcriptions();
	}
	public synchronized void setAnnounceEditionFound(long announceEditionFound) {
		if(lastFinishedAnnounceEdition + 1 == announceEditionFound) {
			lastFinishedAnnounceEdition = announceEditionFound;
			while(announceEditionFinished.contains(lastFinishedAnnounceEdition + 1)) {
				lastFinishedAnnounceEdition = lastFinishedAnnounceEdition + 1;
				announceEditionFinished.remove(lastFinishedAnnounceEdition);
			}
		} else {
			announceEditionFinished.add(announceEditionFound);
		}
	}
	public long getLatestFinishedAnnounceEdition() {
		// for inserting
		return lastFinishedAnnounceEdition;
	}
	public long getAnnounceEdition(){
		// for fetching
		return this.announceEdition;
	}
	public void setAnnounceEdition(long newAnnounceEdition){
		this.announceEdition=newAnnounceEdition;
		this.announceKey="KSK@" + this.config.messageBase + "|"+ this.currentDateString + "|Announce|" + this.announceEdition;
	}
	public String getCurrentDateString() {
		return this.currentDateString;
	}
	public String getCurrentUtcDate(){
		return sdf.format(new Date());
	}
	public void setCurrentDateString(String newUtcDate){
		this.currentDateString=newUtcDate;
		this.announceKey="KSK@" + this.config.messageBase + "|"+ this.currentDateString + "|Announce|" + this.announceEdition;
	}
	public String getMessageBase() {
		return this.config.messageBase;
	}
	public void setMessageBase(String newMessageBase) {
		this.config.messageBase=newMessageBase;
		this.announceKey="KSK@" + this.config.messageBase + "|"+ this.currentDateString + "|Announce|" + this.announceEdition;
	}
	public String getAnnounceKey(){
		return this.announceKey;
	}
	public String getNick(String identRequestKey) {
		return userMap.get(identRequestKey).nick;
	}
	public String getOriginalNick(String identRequestKey) {
		return userMap.get(identRequestKey).originalNick;
	}
	public void setNick(String identRequestKey, String newNick) {
		setNick(identRequestKey, newNick, newNick);
	}
	public void setNick(String identRequestKey, String newNick, String newOriginalNick) {
		userMap.get(identRequestKey).nick = newNick;
		userMap.get(identRequestKey).originalNick = newOriginalNick;
	}
	public long getIdentEdition(String identRequestKey) {
		return userMap.get(identRequestKey).identEdition;
	}
	public void setIdentEdition(String identRequestKey, long identEdition) {
		userMap.get(identRequestKey).identEdition = identEdition;
	}
		
	public long getMessageEditionHint(String identRequestKey) {
		return userMap.get(identRequestKey).messageEditionHint;
	}
	public void setMessageEditionHint(String identRequestKey, long messageEditionHint) {
		userMap.get(identRequestKey).messageEditionHint = messageEditionHint;
	}
	
	public String getRSA(String identRequestKey) {
		return userMap.get(identRequestKey).RSA;
	}
	public void setRSA(String identRequestKey, String RSAkey) {
		userMap.get(identRequestKey).RSA = RSAkey;
	}
	public String getIdentIconHash(String identPubkey){
		return identPubkey;
	}
}
