package plugins.FLIC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import plugins.FLIC.freenetMagic.WebInterface;
import plugins.FLIC.storage.RAMstore;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.PluginReplySender;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.clients.http.PageMaker;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContainer;

public class FLIC implements FredPlugin, FredPluginThreadless, FredPluginL10n, FredPluginFCP {
	private ToadletContainer mFredWebUI;
	private PageMaker mPageMaker;
	private Toadlet mToadlet;
	private Worker mThreadWorker;
	private RAMstore mStorage;

	public void runPlugin(PluginRespirator pr) {
		
		StringBuilder errorMessage = new StringBuilder();
		// TODO: read/store joined channels if autojoin == false;
		mStorage = new RAMstore();
		File dir = new File("FLIC");
		if(!dir.isDirectory()) {
			dir.mkdir();
		}
		if(readConfiguration(pr, errorMessage)) {
			mThreadWorker = new Worker(pr, mStorage);
			mFredWebUI = pr.getToadletContainer();
			mPageMaker = pr.getPageMaker();
			// create a new toadlet for /FLIC/ namespace
			mToadlet = new WebInterface(pr, "/FLIC/", mStorage, pr.getNode().clientCore.formPassword, mThreadWorker);
			// use the last parameter called menuOffset to change the display order (0 = left, no parameter = right)
			mPageMaker.addNavigationCategory(mToadlet.path(), "FLIC", "Freenet Library for Interactive Communication", this);
			// add first visible navigation item
			if(mStorage.config.AllowFullAccessOnly) {
				mFredWebUI.register(mToadlet, "FLIC", mToadlet.path() + "options" , true, "Options", "configure FLIC", true, null, this);
				mFredWebUI.register(mToadlet, "FLIC", mToadlet.path() + "stats" , true, "Statistics", "show statistics", true, null, this);
				// add another hidden navigation item to catch a click on main navigation category
				mFredWebUI.register(mToadlet, null, mToadlet.path(), true, true);
			} else {
				mFredWebUI.register(mToadlet, "FLIC", mToadlet.path() + "options" , true, "Options", "configure FLIC", false, null, this);
				mFredWebUI.register(mToadlet, "FLIC", mToadlet.path() + "stats" , true, "Statistics", "show statistics", false, null, this);
				// add another hidden navigation item to catch a click on main navigation category
				mFredWebUI.register(mToadlet, null, mToadlet.path(), true, false);
			}
			mThreadWorker.start();
		} else {
			System.err.println("[FLIC] can't start FLIC because of invalid configuration file. please fix the following problems and reload FLIC on the plugin page of your node.");
			for(String error : errorMessage.toString().split("\n")) {
				System.err.println("[FLIC] " + error);
			}
			mStorage = null;
			errorMessage = null;
		}
	}
	
	public void terminate() {
		try {
			mThreadWorker.terminate();
			mFredWebUI.unregister(mToadlet);				// unload toadlet
			mPageMaker.removeNavigationCategory("FLIC");	// unload category
		} catch (NullPointerException e) {
			// ignore. FLIC was not started successful.
		}
	}

	private boolean readConfiguration(PluginRespirator pr, StringBuilder errorMessage) {
		Properties configProps = new Properties();
		FileInputStream in;
		boolean success = true;
		try {
			in = new FileInputStream("FLIC/config");
			configProps.load(in);
			in.close();
			if(configProps.size() == 0) {
				return true;
			}
		} catch (FileNotFoundException e) {
			System.out.println("[FLIC] can't load configuration file freenet_directory/FLIC/config. assuming first start.");
		} catch (IOException e) {
			success = false;
			errorMessage.append("can't read configuration file freenet_directory/FLIC/config. please check your file permissions. " + e.getMessage() + "\n");
		} catch (IllegalArgumentException e) {
			success = false;
			errorMessage.append("at least one configuration property found in your configuration file is invalid. please do not manually modify the configuration file. " + e.getMessage() + "\n");
		}
		if(success) {
			mStorage.config.firstStart = Boolean.parseBoolean(configProps.getProperty("firstStart", "true"));
		}
		return success;
	}
	
	@Override
	public String getString(String key) {
		return key;
	}

	@Override
	public void setLanguage(LANGUAGE newLanguage) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void handle(PluginReplySender replysender, SimpleFieldSet params, Bucket data, int accesstype) {
		mThreadWorker.getFCPParser().addFCPclientCommand(replysender, params);
	}
}
