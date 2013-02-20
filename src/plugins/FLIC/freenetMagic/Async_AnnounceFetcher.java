package plugins.FLIC.freenetMagic;

import java.io.IOException;
import java.net.MalformedURLException;

import plugins.FLIC.Worker;
import plugins.FLIC.storage.RAMstore;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;

public class Async_AnnounceFetcher implements ClientGetCallback, RequestClient {
	private RAMstore mStorage;
	private Worker mPtrWorker;
	private HighLevelSimpleClient mFetcher;
	public short concurrentFetchCount;
	public Boolean isRunning;
	
	public Async_AnnounceFetcher(Worker ptrWorker, RAMstore storage, HighLevelSimpleClient fetcher) {
		this.mPtrWorker = ptrWorker;
		this.mStorage = storage;
		this.mFetcher = fetcher;
		this.concurrentFetchCount = 0;
	}
	
	public void startFetching() {
		this.isRunning = true;
		try {
			for(short i = 0; i < mStorage.config.concurrentAnnounceFetcher; i ++) {
				mStorage.setAnnounceEdition(mStorage.getAnnounceEdition() +1);
				addRequest(new FreenetURI(mStorage.getAnnounceKey()));
				concurrentFetchCount += 1;
			}
		} catch (MalformedURLException e) {
			System.err.println("[Async_AnnounceFetcher]::startFetching() MalformedURLException: " + e.getMessage());
			e.printStackTrace();
		}
	}
	
	private void startRequestForNewEdition() {
		try {
			concurrentFetchCount -= 1;
			mStorage.setAnnounceEdition(mStorage.getAnnounceEdition() +1);
			addRequest(new FreenetURI(mStorage.getAnnounceKey()));
			concurrentFetchCount += 1;
		} catch (MalformedURLException e) {
			System.err.println("[Async_AnnounceFetcher]::startRequestForNewEdition() MalformedURLException: " + e.getMessage());
			e.printStackTrace();
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
		if(isRunning) {
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
			mFetchContext.maxRecursionLevel = 0; //?
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
				//System.err.println("[Async_AnnounceFetcher]::addRequest() " + uri.toString());
				mFetcher.fetch(uri, this, this, mFetchContext, (short) 1);
			} catch (FetchException e) {
				System.err.println("[Async_AnnounceFetcher]::addRequest() FetchException: " + e.getMessage());
			}
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

	@Override
	public void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
		switch (e.getMode()) {
		case FetchException.RECENTLY_FAILED:
			// pretty normal for polling.. just add the request again
			if(state.getURI().toString().contains(mStorage.getCurrentDateString())) {
				restartRequest(state.getURI());
			} else {
				startRequestForNewEdition();
			}
			break;
		case FetchException.DATA_NOT_FOUND:
			// pretty normal for polling.. just add the request again
			if(state.getURI().toString().contains(mStorage.getCurrentDateString())) {
				restartRequest(state.getURI());
			} else {
				startRequestForNewEdition();
			}
			break;
		case FetchException.ALL_DATA_NOT_FOUND:
			// should not possible while fetching KSKs without following redirects. ?
			System.err.println("[Async_AnnounceFetcher] ALL_DATA_NOT_FOUND. you should not see me. ignoring this announce. " + e.getMessage() + " " + state.getURI().toString());
			startRequestForNewEdition();
			break;
		case FetchException.ROUTE_NOT_FOUND:
			// if hit we are trying to fetch something but the node does not have a proper connection.
			// just add the request again
			if(state.getURI().toString().contains(mStorage.getCurrentDateString())) {
				restartRequest(state.getURI());
			} else {
				startRequestForNewEdition();
			}
			break;
		case FetchException.REJECTED_OVERLOAD:
			// just add the request again
			if(state.getURI().toString().contains(mStorage.getCurrentDateString())) {
				restartRequest(state.getURI());
			} else {
				startRequestForNewEdition();
			}
			break;
		case FetchException.INVALID_METADATA:
			// wtf?
			mStorage.announce_ddos +=1;
			System.err.println("[Async_AnnounceFetcher] INVALID_METADATA. you should not see me. ignoring this announce. " + e.getMessage() + " " + state.getURI().toString());
			startRequestForNewEdition();
			break;
		case FetchException.TOO_BIG_METADATA:
			// wtf? 
			mStorage.announce_ddos +=1;
			System.err.println("[Async_AnnounceFetcher] TOO_BIG_METADATA. you should not see me. ignoring this announce. " + e.getMessage() + " " + state.getURI().toString());
			startRequestForNewEdition();
			break;
		case FetchException.TOO_BIG:
			// should not be possible while polling KSK's without following redirects
			mStorage.announce_ddos +=1;
			System.err.println("[Async_AnnounceFetcher] TOO_BIG. you should not see me. ignoring this announce. " + e.getMessage() + " " + state.getURI().toString());
			startRequestForNewEdition();
			break;
		case FetchException.TOO_MANY_REDIRECTS:
			mStorage.announce_ddos +=1;
			System.err.println("[Async_AnnounceFetcher] TOO_MANY_REDIRECTS. you should not see me. ignoring this announce. " + e.getMessage() + " " + state.getURI().toString());
			startRequestForNewEdition();
			break;
		case FetchException.TOO_MUCH_RECURSION:
			// FIXME: wtf?
			mStorage.announce_ddos +=1;
			System.err.println("[Async_AnnounceFetcher] TOO_MUCH_RECURSION. you should not see me. ignoring this announce. " + e.getMessage() + " " + state.getURI().toString());
			startRequestForNewEdition();
			break;
		case FetchException.PERMANENT_REDIRECT:
			mStorage.announce_ddos +=1;
			System.err.println("[Async_AnnounceFetcher] TOO_MUCH_RECURSION. you should not see me. ignoring this announce. " + e.getMessage() + " " + state.getURI().toString());
			startRequestForNewEdition();
			break;
		default:
			// now we have a serious problem.
			mStorage.announce_ddos +=1;
			System.err.println("[Async_AnnounceFetcher]::onFailure() unknown error: " + e.getMessage() + " mode=" + e.getMode() + " ignoring this announce. uri=" + state.getURI().toString());
			e.printStackTrace();
			startRequestForNewEdition();
			break;
		}
	}

	@Override
	public void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
		try {
			Long announceEditionFound = Long.parseLong(state.getURI().toString().substring(state.getURI().toString().lastIndexOf('c') + 1));
			mStorage.setAnnounceEditionFound(announceEditionFound);
			mPtrWorker.getMessageParser().addMessage("announce", "", new String(result.asByteArray()).trim(), state.getURI().toString());
			startRequestForNewEdition();
		} catch (IOException e) {
			System.err.println("[Async_AnnounceFetcher]::onSuccess() IOException. " + e.getMessage());
			e.printStackTrace();
		}
	}
	

}
