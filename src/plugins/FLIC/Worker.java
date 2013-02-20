package plugins.FLIC;

import java.net.MalformedURLException;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import com.db4o.ObjectContainer;

import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientPutCallback;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import plugins.FLIC.freenetMagic.Async_AnnounceFetcher;
import plugins.FLIC.freenetMagic.FreenetFCPParser;
import plugins.FLIC.freenetMagic.FreenetFCPParser.InsertMessage;
import plugins.FLIC.freenetMagic.FreenetMessageParser;
import plugins.FLIC.freenetMagic.USK_IdentityFetcher;
import plugins.FLIC.freenetMagic.USK_MessageFetcher;
import plugins.FLIC.storage.RAMstore;

public class Worker extends Thread implements ClientPutCallback  {
	private RAMstore mStorage;
	private FreenetMessageParser mFreenetMessageParser;
	private HighLevelSimpleClient mInserter;
	private Deque<InsertMessage> mQueue;
	private BucketFactory mTmpBucketFactory;
	private Async_AnnounceFetcher mAsyncAnnounceFetcher;
	private USK_IdentityFetcher mIdentityFetcher_usk;
	private USK_MessageFetcher mMessageFetcher_usk;
	private FreenetFCPParser mFreenetFCPParser;
	private float processingTime = 0;
	private volatile boolean isRunning;
	private ConcurrentHashMap<String, InsertMessage> mCurrentlyInserting;
	
	public void terminate() {
		mAsyncAnnounceFetcher.isRunning = false;
		mIdentityFetcher_usk.removeAllFetchers();
		mMessageFetcher_usk.removeAllFetchers();
		mFreenetMessageParser.terminate();
		mFreenetFCPParser.terminate();
		isRunning = false;
		this.interrupt();
	}
	
	public Worker(PluginRespirator pr, RAMstore Storage) {
		this.setName("FLIC.worker");
		this.mStorage = Storage;
		Storage.setPtrWorker(this);
		// FIXME: use pointer for worker and assign Fetchers and Parser in new instances from the worker pointer
		this.mAsyncAnnounceFetcher = new Async_AnnounceFetcher(this, Storage, pr.getNode().clientCore.makeClient((short) 1, false, true));
		this.mIdentityFetcher_usk = new USK_IdentityFetcher(this, Storage, pr);
		this.mMessageFetcher_usk = new USK_MessageFetcher(this, Storage, pr);
		this.mFreenetFCPParser = new FreenetFCPParser(this, Storage);
		this.mFreenetMessageParser = new FreenetMessageParser(this, Storage);
		// insert at realtime prio 1.
		this.mInserter = pr.getNode().clientCore.makeClient((short) 1, false, true);
		this.mQueue = new ArrayDeque<InsertMessage>();
		this.mTmpBucketFactory = pr.getNode().clientCore.tempBucketFactory;
		this.mCurrentlyInserting = new ConcurrentHashMap<String, InsertMessage>();
		this.mFreenetMessageParser.start();
		this.mFreenetFCPParser.start();
		this.mAsyncAnnounceFetcher.startFetching();
	}
	
	public Async_AnnounceFetcher getAnnounceFetcher() {
		return mAsyncAnnounceFetcher;
	}
	public USK_IdentityFetcher getIdentityFetcher() {
		return mIdentityFetcher_usk;
	}
	public USK_MessageFetcher getMessageFetcher() {
		return mMessageFetcher_usk;
	}
	public FreenetMessageParser getMessageParser() {
		return mFreenetMessageParser;
	}
	
	public FreenetFCPParser getFCPParser() {
		return mFreenetFCPParser;
	}
	
	public HighLevelSimpleClient getHighLevelSimpleClient() {
		return mInserter;
	}
	
	public BucketFactory getBucketFactory() {
		return mTmpBucketFactory;
	}
	
	public int getCurrentQueueSize() {
		return mCurrentlyInserting.size();
	}
	public float getProcessingTime() {
		return processingTime / 1000;
	}
	public synchronized boolean insertMessage(InsertMessage message) {
		return insertMessage(message, false);
	}
	
	public synchronized boolean insertMessage(InsertMessage message, boolean addTop) {
		// inserts message into queue
		if(message == null) {
			return false;
		}
		if(addTop) {
			mQueue.addFirst(message);
		} else {
			mQueue.addLast(message);
		}
		//mStorage.userMap.get(mStorage.config.requestKey).lastMessageTime = new Date().getTime();
		return true;
	}
	
	@Override
	public void run() {
		// count tcp packets. if its odd multiply with 3, add 1. if its even divide it by two. why? because we can.
		isRunning = true;
		InsertMessage currentJob;
		while(!isInterrupted() && isRunning) {
			if(!mStorage.getCurrentDateString().equals(mStorage.getCurrentUtcDate())) {
				mStorage.setCurrentDateString(mStorage.getCurrentUtcDate());
				mStorage.setAllEditionsToZero();
			}
			mStorage.checkUserActivity();
//			if(!mStorage.config.firstStart && now - lastKeepAliveInsert > 14 * 60 * 1000) {
//				if(mStorage.channelList.size() > 0) {
//					SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//					sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
//					String message = "channels=";
//					String topics = "";
//					if(mStorage.config.autojoinChannel) {
//						for(Channel chan : mStorage.channelList) {
//							message += chan.name + " ";
//							if(!chan.topic.equals("")) {
//								topics += "topic." + chan.name.replace("=", ":") + "=" + chan.topic + "\n";
//							}
//						}
//					} else {
//						Channel chan;
//						for(String channelName : mStorage.config.joinedChannels) {
//							chan = mStorage.getChannel(channelName);
//							message += chan.name + " ";
//							if(!chan.topic.equals("")) {
//								topics += "topic." + chan.name.replace("=", ":") + "=" + chan.topic + "\n";
//							}
//						}
//					}
//					if(message.endsWith(" ")) {
//						message = message.substring(0, message.length() - 1);
//					}
//					message +=  "\n";
//					message += "sentdate=" + sdf.format(new Date().getTime()) + "\n";
//					message += "type=keepalive\n";
//					if(message.length() + topics.length() + 1 < 1025) {
//						message += topics;
//					} else {
//						// TODO: add round robin
//						for(String line : topics.split("\n")) {
//							if(message.length() + line.length() + 2 < 1025) {
//								message += line + "\n";
//							}
//						}
//					}
//					message += "\n";
//					insertMessage(mStorage.new PlainTextMessage("message", message, "", "", 0, 0));
//					lastKeepAliveInsert = now;
//				}
//			}
			currentJob = mQueue.pollFirst();
			if(currentJob == null) {
				// nothing in queue
				try {
					sleep(100);
				} catch (InterruptedException e) {
					// ignore. FLIC.terminate() will set isRunning == false
				}				
			} else {
				startInsert(currentJob);
			}
		}
	}

	private void startInsert(InsertMessage currentJob) {
		InsertBlock mTmpInsertBlock;
		try {
			FreenetURI uri = new FreenetURI(currentJob.insertKey + currentJob.edition);
			mTmpInsertBlock = new InsertBlock(currentJob.bucket, null, uri);
			InsertContext mInsertContext = mInserter.getInsertContext(true);
			mInsertContext.maxInsertRetries = -1;
			mCurrentlyInserting.put(new FreenetURI(currentJob.identifier + currentJob.edition).toString(), currentJob);
			mInserter.insert(mTmpInsertBlock, false, null, false, mInsertContext, this, (short) 1);
		} catch (MalformedURLException e) {
			System.err.println("[Worker]::startInsert() MalformedURLException while inserting temporary bucket into " + currentJob.identifier + ". " + e.getMessage());
		} catch (InsertException e) {
			System.err.println("[Worker]::startInsert() InsertException while writing message bytes to temporary bucket. tried to insert " + currentJob.identifier + ". " + e.getMessage());
		}
	}
	
	@Override
	public void onSuccess(BaseClientPutter state, ObjectContainer container) {
		InsertMessage currentJob = mCurrentlyInserting.get(state.getURI().toString());
		mCurrentlyInserting.remove(state.getURI().toString());
		processingTime = new Date().getTime() - currentJob.startedAt;
		currentJob.fcp_callback.onInsertFinished(currentJob);
	}
	
	@Override
	public void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
		InsertMessage currentJob = mCurrentlyInserting.get(state.getURI().toString());
		mCurrentlyInserting.remove(state.getURI().toString());
		if(currentJob == null) {
			System.err.println("[Worker]::onFailure() currentJob == null. wtf? state.getURI().toString()=" + state.getURI().toString());
			return;
		}
		if(e.getMode() == InsertException.COLLISION) {
			currentJob.fcp_callback.onInsertFailed(currentJob, e);
		} else if(e.getMode() == InsertException.REJECTED_OVERLOAD) {
			System.err.println("[Worker]::onFailure() REJECTED_OVERLOAD while inserting message. " + e.getMessage() + ". errorNr: " + e.getMode() + ". restarting insert.");
			// just add the job to the queue again.
			mQueue.addFirst(currentJob);
		} else {
			System.err.println("[Worker]::onFailure() InsertException while inserting message " + currentJob.identifier + ". " + e.getMessage() + ". errorNr: " + e.getMode());
		}
	}

	@Override
	public void onMajorProgress(ObjectContainer container) {
	}

	@Override
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state, ObjectContainer container) {
	}

	@Override
	public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state, ObjectContainer container) {
	}

	@Override
	public void onFetchable(BaseClientPutter state, ObjectContainer container) {		
	}
}
