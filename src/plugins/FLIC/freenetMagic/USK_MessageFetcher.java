package plugins.FLIC.freenetMagic;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;

import plugins.FLIC.Worker;
import plugins.FLIC.storage.RAMstore;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.USKCallback;
import freenet.client.async.USKManager;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.RequestClient;
import freenet.pluginmanager.PluginRespirator;

public class USK_MessageFetcher implements USKCallback, ClientGetCallback, RequestClient {
	private RAMstore mStorage;
	private Worker mPtrWorker;
	private HighLevelSimpleClient mFetcher;
	private USKManager mUSKmanager;
	private List<USK> mSubscriptions;
	public int concurrentFetchCount;
	private HashMap<FreenetURI, Short> mDNFtracker;
	
	
	public USK_MessageFetcher(Worker ptrWorker, RAMstore storage, PluginRespirator pr) {
		this.mPtrWorker = ptrWorker;
		this.mStorage = storage;
		this.mUSKmanager = pr.getNode().clientCore.uskManager;
		this.mSubscriptions = new ArrayList<USK>();
		this.concurrentFetchCount = 0;
		this.mFetcher = pr.getNode().clientCore.makeClient((short) 1, false, true);
	}
	public void removeAllFetchers() {
		for(USK usk : mSubscriptions) {
			mUSKmanager.unsubscribe(usk, this);
		}
	}
	public void removeSingleFetcher(String ident) {
		String curIdent;
		for(USK usk : mSubscriptions) {
			curIdent = usk.getURI().toString().split("/",2)[0].replace("USK@", "SSK@") + "/"; 
			if(ident.equals(curIdent)) {
				mUSKmanager.unsubscribe(usk, this);
				mSubscriptions.remove(usk);
				break;
			}
		}
	}
	public int getSubscriptionCount() {
		return mSubscriptions.size();
	}
	
	public void addInitialMessageSubscription(String identRequestKey) {
		FreenetURI identRequestURI = null;
		try {
			identRequestURI = new FreenetURI(identRequestKey).setKeyType("USK").setDocName(mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Message");
			identRequestURI = identRequestURI.setSuggestedEdition(mStorage.getMessageEditionHint(identRequestKey)); //.setMetaString(null);
			addRequest(identRequestURI);
		} catch (MalformedURLException e) {
			System.err.println("[USK_MessageFetcher]::addInitialSubscription identRequestKey = " + identRequestKey);
			System.err.println("[USK_MessageFetcher]::addInitialSubscription MalformedURLException. " + e.getMessage());
			e.printStackTrace();
		}
	}
	
	private void addSubscription(FreenetURI identRequestURI, long latestEdition) {
		USK usk = null;
		try { 
			usk = USK.create(identRequestURI.setSuggestedEdition(latestEdition));
			USK uskIdent = USK.create(identRequestURI.setSuggestedEdition(0));
			if(!mSubscriptions.contains(uskIdent)) {
				mUSKmanager.subscribe(usk, this, true, true, this);
				mSubscriptions.add(uskIdent);
			}
		} catch (MalformedURLException e) {
			if (usk != null) {
				System.err.println("[USK_MessageFetcher]::addSubscription usk = " + usk.getURI().toString());
			} else {
				System.err.println("[USK_MessageFetcher]::addSubscription usk = null. " + e.getMessage());
			}
			e.printStackTrace();
		}

	}
	
	public void resetSubcriptions() {
		List<USK> newSubscriptionList = new ArrayList<USK>();
		try {
			for(USK usk : mSubscriptions) {
				mUSKmanager.unsubscribe(usk, this);
				try {
					usk = USK.create(usk.getURI().setDocName(mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Message").setSuggestedEdition(0));
					mUSKmanager.subscribe(usk, this, true, true, this);
					newSubscriptionList.add(usk);
				} catch (MalformedURLException e) {
					System.err.println("[USK_IdentityFetcher]::resetSubscriptions() MalformedURLException: " + e.getMessage());
				}
			}
			mSubscriptions = newSubscriptionList;
		} catch (ConcurrentModificationException e) {
			// subscription was deleted or added during loop
			resetSubcriptions();
		}
	}

	private void restartRequest(final FreenetURI uri) {
		Thread delayed = new Thread() {
			@Override
			public void run() {
				try {
					sleep(1000);
					addRequest(uri);
				} catch (InterruptedException e) {
					
				}
			}
		};
		delayed.start();
	}
	private void addRequest(FreenetURI uri) {
		FetchContext mFetchContext = mFetcher.getFetchContext();
		mFetchContext.allowSplitfiles = true;		// FIXME: disable as soon as its fixed!
		mFetchContext.canWriteClientCache = true;
		mFetchContext.dontEnterImplicitArchives = true; //?
		mFetchContext.filterData = false; //?
		mFetchContext.followRedirects = false;
		mFetchContext.ignoreStore = false;
		//final? mFetchContext.ignoreTooManyPathComponents = false;
		mFetchContext.ignoreUSKDatehints = true; // ?
		mFetchContext.localRequestOnly = false;
		mFetchContext.maxArchiveLevels = 0; //?
		mFetchContext.maxArchiveRestarts = 0; //?
		mFetchContext.maxCheckBlocksPerSegment = 0; //?
		mFetchContext.maxDataBlocksPerSegment = 0; //?
		//mFetchContext.maxMetadataSize = ?
		// cooldown for 30 minutes, wtf? this is a real time chat plugin.
		//mFetchContext.maxNonSplitfileRetries = -1;
		mFetchContext.maxNonSplitfileRetries = 2;
		//mFetchContext.maxOutputLength = 1024 ?
		mFetchContext.maxRecursionLevel = 1; //?
		mFetchContext.maxSplitfileBlockRetries = 0;
		//mFetchContext.maxTempLength = ?
		//final? mFetchContext.maxUSKRetries = -1; //?
		//mFetchContext.overrideMIME = "text/plain"; //?
		//mFetchContext.prefetchHook = ?
		//mFetchContext.returnZIPManifests = true ?
		//mFetchContext.tagReplacer = ?
		//mFetchContext.setCooldownRetries(cooldownRetries);
		//mFetchContext.setCooldownTime(cooldownTime);
		try { 
			mFetcher.fetch(uri, this, this, mFetchContext, (short) 1);
			concurrentFetchCount += 1;
		} catch (FetchException e) {
			System.err.println("[USK_MessageFetcher]::addRequest() FetchException " + e.getMessage());
		}
	}

	@Override
	public void onMajorProgress(ObjectContainer arg0) {
	}

	@Override
	public boolean persistent() {
		return false;
	}

	@Override
	public boolean realTimeFlag() {
		return true;
	}

	@Override
	public void removeFrom(ObjectContainer arg0) {
	}
	
	
	private void checkRequestRestart(FreenetURI uri, String reason) {
		// non fatal errors. we can simply restart the request
		// FIXME: add hashmap for tracking retries per uri until maxRetries is hit. print error then.
		concurrentFetchCount -= 1;
		//System.err.println("[USK_MessageFetcher] " + reason + ". restarting request. " + uri.toString());
		restartRequest(uri);
	}
	
	private void checkInvalid(FreenetURI uri, String reason, String message) {
		// fatal errors. can't fetch this request. subscribing to current edition or ignoring this edition.
		// FIXME: better stop fetching this identity?
		concurrentFetchCount -= 1; 
		if(uri.isUSK()) {
			// initial USK request, no subscription activated yet.
			// subscribe to current edition
			addSubscription(uri, uri.getEdition());
			System.err.println("[USK_MessageFetcher] " + reason + ". initial request. subscribing anyway. " + uri.toString() + " " + message);
		} else {
			// backed up by subscription. we wait for the next edition and print the error for manual analysis.
			System.err.println("[USK_MessageFetcher] " + reason + " for edition " + uri.getEdition() + ". " + message + "\n" + uri.toString());
		}
	}

	@Override
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		String ident = state.getURI().toString().split("/")[0].replace("USK", "SSK") + "/";
		switch (e.getMode()) {
		case FetchException.RECENTLY_FAILED:
			// just silently refetch
			concurrentFetchCount -= 1;
			restartRequest(state.getURI());
			break;
		case FetchException.DATA_NOT_FOUND:
			if(state.getURI().isUSK()) {
				// initial USK request, no subscription active
				checkRequestRestart(state.getURI(), "DATA_NOT_FOUND");
				break;
			}
			concurrentFetchCount -= 1;
			if(!mDNFtracker.containsKey(state.getURI())) {
				mDNFtracker.put(state.getURI(), (short) 1);
			} else {
				mDNFtracker.put(state.getURI(), (short) (mDNFtracker.get(state.getURI()) + 1));
			}
			if(mDNFtracker.get(state.getURI()) < mStorage.config.maxMessageRetriesAfterDNF +1) {
				restartRequest(state.getURI());
			} else {
				mDNFtracker.remove(state.getURI());
				mStorage.userMap.get(ident).failedMessageRequests.add(state.getURI().toString());
				System.err.println("[USK_MessageFetcher] DATA_NOT_FOUND. maximal numbers of " + mStorage.config.maxMessageRetriesAfterDNF + " retires reached. ignoring message. " + state.getURI().toString());
			}
			break;
		case FetchException.ALL_DATA_NOT_FOUND:
			// should not possible while fetching SSKs without following redirects.
			concurrentFetchCount -= 1;
			System.err.println("[USK_MessageFetcher] ALL_DATA_NOT_FOUND. you should not see me. " + e.getMessage() + " " + state.getURI().toString());
			break;
		case FetchException.ROUTE_NOT_FOUND:
			// if hit it we are trying to fetch something but the node does not have a proper connection.
			checkRequestRestart(state.getURI(), "ROUTE_NOT_FOUND");
			break;
		case FetchException.REJECTED_OVERLOAD:
			checkRequestRestart(state.getURI(), "REJECTED_OVERLOAD");
			break;
		case FetchException.INVALID_METADATA:
			// wtf?
			mStorage.message_ddos +=1;
			mStorage.userMap.get(ident).message_ddos += 1;
			checkInvalid(state.getURI(), "INVALID_METADATA", e.getMessage());
			break;
		case FetchException.TOO_BIG_METADATA:
			// wtf? 
			mStorage.message_ddos +=1;
			mStorage.userMap.get(ident).message_ddos += 1;
			checkInvalid(state.getURI(), "TOO_BIG_METADATA", e.getMessage());
			break;
		case FetchException.TOO_BIG:
			// should not be possible while polling SSK's without following redirects
			mStorage.message_ddos +=1;
			mStorage.userMap.get(ident).message_ddos += 1;
			checkInvalid(state.getURI(), "TOO_BIG", e.getMessage());
			break;
		case FetchException.TOO_MANY_REDIRECTS:
			checkInvalid(state.getURI(), "TOO_MANY_REDIRECTS", e.getMessage());
			break;
		case FetchException.TOO_MUCH_RECURSION:
			// FIXME: wtf?
			concurrentFetchCount -= 1;
			if(state.getURI().isUSK()) {
				System.err.println("[USK_MessageFetcher] TOO_MUCH_RECURSION for initial USK. subscribing to current edition " + state.getURI().getEdition());
				addSubscription(state.getURI(), state.getURI().getEdition());
			} else {
				System.err.println("[USK_MessageFetcher] TOO_MUCH_RECURSION for SSK. wtf? " + e.getMessage() + " " + state.getURI().toString());
			}
			break;
		case FetchException.PERMANENT_REDIRECT:
			concurrentFetchCount -= 1;
			if(e.newURI.toString().startsWith(state.getURI().toString().split("/")[0])) {
				// this should work only for USK requests. we request USK only for the initial subscription so this should be save.
				addRequest(e.newURI);
				//System.err.println("[USK_MessageFetcher] PERMANENT_REDIRECT for initial USK. got new edition. old edition was " + state.getURI().getEdition() + " new edition is " + e.newURI.getEdition() + " usk=" + state.getURI().toString());
			} else {
				mStorage.message_ddos +=1;
				mStorage.userMap.get(ident).message_ddos += 1;
				System.err.println("[USK_MessageFetcher] got illegal redirection. old URI: " + state.getURI().toString() + " new URI: " + e.newURI.toString());
			}
			break;
		default:
			// now we have a serious problem.
			concurrentFetchCount -= 1;
			mStorage.message_ddos +=1;
			mStorage.userMap.get(ident).message_ddos += 1;
			System.err.println("[USK_MessageFetcher]::onFailure::default::else: " + e.getMessage() + " mode=" + e.getMode() + " uri=" + state.getURI().toString());
			break;
		}
	}

	@Override
	public void onSuccess(FetchResult result, ClientGetter state,
			ObjectContainer container) {
		String ident = state.getURI().toString().split("/", 2)[0].replace("USK@","SSK@") + "/";
		concurrentFetchCount -= 1;
		if(state.getURI().toString().startsWith("USK@")) {
			//System.err.println("[USK_MessageFetcher]::onSuccess key type = initial USK");
			// only the initial request is a USK request. all requests issued by
			// polling the USK for new editions use SSK. see below at onFoundEdition().
			//System.err.println("[USK_MessageFetcher] onSuccess(). got content for " + state.getURI().toString() + ". subscribing to current edition " + state.getURI().getEdition());
			mStorage.setMessageEditionHint(ident, state.getURI().getEdition());
			addSubscription(state.getURI(), state.getURI().getEdition());
		}
		try {
			mPtrWorker.getMessageParser().addMessage("message", ident, new String(result.asByteArray()).trim(), state.getURI().toString());
		} catch (IOException e) {
			System.err.println("[USK_MessageFetcher]::onSuccess IOException. " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Override
	public short getPollingPriorityNormal() {
		return (short) 1;
	}

	@Override
	public short getPollingPriorityProgress() {
		return (short) 1;
	}

	@Override
	public void onFoundEdition(long l, USK key, ObjectContainer container,
			ClientContext context, boolean metadata, short codec, byte[] data,
			boolean newKnownGood, boolean newSlotToo) {
		String ident = key.getURI().toString().split("/", 2)[0].replace("USK@", "SSK@") + "/";
		long oldHint = mStorage.getMessageEditionHint(ident);
		if(l > oldHint) {
			mStorage.setMessageEditionHint(ident, l);
			String newKeyBase = ident + mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Message-";
			long i = oldHint +1;
			try {
				Boolean finnished = false;
				while(!finnished) {
					//System.err.println("[USK_MessageFetcher] adding new SSK fetcher: " + newKeyBase + i);
					addRequest(new FreenetURI(newKeyBase + i).setMetaString(null));
					i += 1;
					if(i > l) { finnished = true; }
				}
			} catch (MalformedURLException e) {
				System.err.println("[USK_MessageFetcher] adding new SSK fetcher: " + newKeyBase + i + " failed. " + e.getMessage());
				e.printStackTrace();
			}
		}
	}

}
