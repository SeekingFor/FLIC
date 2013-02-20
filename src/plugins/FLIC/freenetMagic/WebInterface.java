package plugins.FLIC.freenetMagic;

import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Properties;
import java.util.TimeZone;

import javax.imageio.ImageIO;
import plugins.FLIC.Worker;
import plugins.FLIC.freenetMagic.identicon.Identicon;
import plugins.FLIC.storage.RAMstore;
import plugins.FLIC.storage.RAMstore.Channel;
import plugins.FLIC.storage.RAMstore.User;
import freenet.clients.http.PageNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.IllegalBase64Exception;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;

public class WebInterface extends Toadlet {
	private String mNameSpace;
	private RAMstore mStorage;
	private String mFormPassword;
	private Worker mPtrWorker;
	private BucketFactory mPtrTmpBucketFactory;
	
	public WebInterface(PluginRespirator pr, String path, RAMstore Storage, String formPassword, Worker ptrWorker) {
		super(pr.getHLSimpleClient());
		mNameSpace = path;
		mStorage = Storage;
		mFormPassword = formPassword;
		mPtrWorker = ptrWorker;
		mPtrTmpBucketFactory = pr.getNode().clientCore.tempBucketFactory;
	}
	
	@Override
	public String path() {
		return mNameSpace;
	}
	
	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		// This method is called whenever a user requests a page from our mNameSpace
		handleWebRequest(uri, req, ctx);
	}
	public void handleMethodPOST(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		// This method is called whenever a user requests a page from our mNameSpace
		
		// POST form authentication
		//FIXME link the core
		//FIXME validate referer
		//FIXME validate session
		//String passwordPlain = req.getPartAsString("formPassword", 32);
		//if((passwordPlain.length() == 0) || !passwordPlain.equals(core.formPassword)) {
		//	writeHTMLReply(ctx, 403, "Forbidden", "Invalid form password.");
		//	return;
		//}
		handleWebRequest(uri, req, ctx);
	}
	private void handleWebRequest(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		// We check the requested URI against a whitelist. Any request not found here will result in a info page.
		if(mStorage.config.AllowFullAccessOnly && !ctx.isAllowedFullAccess()) {
			// full access not allowed for the requesting ip
			writeHTMLReply(ctx, 403, "", "Your host is not allowed to access this page.<br />Try adding it to 'Hosts having a full access to the Freenet web interface (read warning)' in your <a href='../config/fproxy'>fproxy configuration</a>.");
		}
		String requestedPath = uri.toString().replace(mNameSpace, "");
		PageNode mPageNode;
		if(requestedPath.equals("")) {
			writeHTMLReply(ctx, 200, "OK", createRoot(req, ctx).outer.generate());
		} else if(requestedPath.equals("options")) {
			if(mStorage.config.firstStart) {
				mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to FLIC", true, ctx);
				mPageNode = createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
			} else {
				mPageNode = ctx.getPageMaker().getPageNode("FLIC configuration", true, ctx);
				mPageNode = createConfig(mPageNode);
			}
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else if(requestedPath.equals("stats")) {
			writeHTMLReply(ctx, 200, "OK", createStats(req, ctx).outer.generate());
		} else if(requestedPath.startsWith("getIdenticon")) {
				byte[] routingKey;
				try {
					routingKey = Base64.decode(req.getParam("ident"));
					RenderedImage identiconImage =  new Identicon(routingKey).render(120, 120);
					ByteArrayOutputStream imageOutputStream = new ByteArrayOutputStream();
					ImageIO.write(identiconImage, "png", imageOutputStream);
					Bucket imageBucket = BucketTools.makeImmutableBucket(mPtrTmpBucketFactory, imageOutputStream.toByteArray());
					writeReply(ctx, 200, "image/png", "OK", imageBucket);
					imageBucket.free();
					Closer.close(imageOutputStream);
				} catch (IllegalBase64Exception e) {
					writeReply(ctx, 204, "text/plain", "not found", "not found");
				}
		} else if(requestedPath.equals("receiver")) {
			writeHTMLReply(ctx, 200, "OK", handleReceivedInput(req,ctx).outer.generate());
		} else if(requestedPath.startsWith("profile?ident=")) {
			mPageNode = ctx.getPageMaker().getPageNode("FLIC profile", true, ctx);
			mPageNode = createProfilePage(mPageNode, requestedPath.split("=")[1]);
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else {
			writeHTMLReply(ctx, 200, "OK", createRequestInfo(req, ctx).outer.generate());
		}
	}
	private String basicHTMLencode(String input) {
		//& → &amp;
		//< → &lt;
		//> → &gt;
		//' → &#39;
		//" → &quot;
		//\n→ <br />
		input = input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;").replace("\n", "<br />");
		Boolean bOpened = false;
		Boolean iOpened = false;
		Boolean uOpened = false;
		StringBuilder builder = new StringBuilder();
		for(char character : input.toCharArray()) {
			if(character == 0x02) {
				// bold
				if(!bOpened) {
					builder.append("<b>");
					bOpened = true;
				} else {
					builder.append("</b>");
					bOpened = false;
				}
			} else if(character == 0x1D) {
				// italics
				if(!iOpened) {
					builder.append("<i>");
					iOpened = true;
				} else {
					builder.append("</i>");
					iOpened = false;
				}
			} else if(character == 0x1F) {
				// underline
				if(!uOpened) {
					builder.append("<u>");
					uOpened = true;
				} else {
					builder.append("</u>");
					uOpened = false;
				}
			} else {
				builder.append(character);
			}
		}
		if(bOpened) { builder.append("</b>"); }
		if(iOpened) { builder.append("</i>"); }
		if(uOpened) { builder.append("</u>"); }
		return builder.toString();
	}

	private HTMLNode parseWelcomeCreateLink(String tag) {
		HTMLNode aNode = new HTMLNode("a", tag);
		if(tag.equals("FLIP")) {
			aNode.addAttribute("href", "../USK@pGQPA-9PcFiE3A2tCuCjacK165UaX07AQYw98iDQrNA,8gwQ67ytBNR03hNj7JU~ceeew22HVq6G50dcEeMcgks,AQACAAE/flip/9/");
		} else if(tag.equals("Freenet Social Network Guide for FLIP")) {
			aNode.addAttribute("href", "../USK@t5zaONbYd5DvGNNSokVnDCdrIEytn9U5SSD~pYF0RTE,guWyS9aCMcywU5PFBrKsMiXs7LzwKfQlGSRi17fpffc,AQACAAE/fsng/37/flip.html");
		} else {
			aNode.addAttribute("href", "not found");
		}
		aNode.addAttribute("target", "_blank");
		return aNode;
	}
	private HTMLNode parseWelcomeMessage(String message) {
		// TODO: use freenets html parser instead?
		// TODO: use own parser basicHTMLencode() instead?
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		String buffer;
		for(String line : message.split("\n")) {
			buffer = "";
			for(String word : line.split(" ")) {
				if(!buffer.equals("") && (word.startsWith("[a]") || word.startsWith("[b]"))) {
					// TODO: uh? check this^ again. should be !(word.startsWith() || word.startsWith())?
					ownContentNode.addChild(new HTMLNode("span", buffer));
					buffer = "";
				}
				if(word.startsWith("[a]") && word.contains("[/a]")) {
					buffer = word.replace("[a]", "").replace("[/a]", "");
					ownContentNode.addChild(parseWelcomeCreateLink(buffer));
					buffer = " ";
				} else if(word.startsWith("[a]") && !word.contains("[/a]")) {
					buffer = word.replace("[a]", "") + " ";
				} else if(word.contains("[/a]")) {
					buffer += word.replace("[/a]", "");
					ownContentNode.addChild(parseWelcomeCreateLink(buffer));
					buffer = " ";
				} else if(word.startsWith("[b]") && word.contains("[/b]")) {
					ownContentNode.addChild(new HTMLNode("b", word.replace("[b]", "").replace("[/b]", "")));
					buffer = " ";
				} else if(word.startsWith("[b]") && !word.contains("[/b]")) {
					buffer = word.replace("[b]", "") + " ";
				} else if(word.contains("[/b]")) {
					buffer += word.replace("[/b]", "");
					ownContentNode.addChild(new HTMLNode("b", buffer));
					buffer = " ";
				} else {
					buffer += word + " ";
				}
			}
			if(!buffer.equals(" ")) {
				ownContentNode.addChild(new HTMLNode("span", buffer));
			}
			ownContentNode.addChild(new HTMLNode("br"));
		}
		return ownContentNode;
	}
	
	private PageNode createProfilePage(PageNode mPageNode, String ident) {
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("id", "FLIC");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		ownContentNode.addChild("br");
		if(mStorage.knownIdents.contains(ident)) {
			String routingKey = ident.split(",")[0].replace("SSK@", "");
			HTMLNode image = new HTMLNode("img");
			image.addAttribute("src", "getIdenticon?ident=" + routingKey);
			ownContentNode.addChild("b", mStorage.getNick(ident));
			ownContentNode.addChild("br");
			ownContentNode.addChild(image);
			mPageNode.content.addChild(ownContentNode);
		} else {
			ownContentNode.addChild("b", "user not found");
			mPageNode.content.addChild(ownContentNode);
		}
		return mPageNode;
	}
	
	private PageNode handleReceivedInput(HTTPRequest req, ToadletContext ctx) {
		// TODO: add functions for parsing
		PageNode mPageNode = ctx.getPageMaker().getPageNode("FLIC configuration", ctx);
//		if(!req.getPartAsStringFailsafe("formPassword",255).equals(mFormPassword)) {
//			mPageNode.content.addChild(new HTMLNode("span", "formPassword does not match. should be " + mFormPassword + " and is " + req.getPartAsStringFailsafe("formPassword",255) + "."));
//			return mPageNode;
//		}
		String input;
		Boolean error = false;
		String errorMsg = "";
		// nick
		input = req.getPartAsStringFailsafe("nick",255);
		if(input.length() >= 16) {
			error = true;
			errorMsg += "nick length must be < 16\n";
		}
		
		// save to configuration file
		Properties configProps = new Properties();
		FileInputStream in;
		FileOutputStream out;
		try {
			in = new FileInputStream("FLIC/config");
			configProps.load(in);
			in.close();
		} catch (FileNotFoundException e) {
			// configuration file does not yet exist or can't be opened for reading
		} catch (IOException e) {
			// file can't be read?
		} catch (IllegalArgumentException e) {
			// configuration file contains at least one invalid property
		}
		configProps.setProperty("nick", input);
		try {
			out = new FileOutputStream("FLIC/config", false);
			configProps.store(out, "configuration for FLIC " + mStorage.config.version_major + "." + mStorage.config.version_minor + "." + mStorage.config.version_release + " created at the configuration page.");
			out.close();
		} catch (FileNotFoundException e) {
			// out stream can't create file
			error = true;
			errorMsg += "failed to create or modify configuration file. please check your file permissions for freenet_directory/FLIC/config. " + e.getMessage() + "\n";
		} catch (IOException e) {
			// configProps can't write to file
			error = true;
			errorMsg += "failed to create or modify configuration file. please check your file permissions for freenet_directory/FLIC/config. " + e.getMessage() + "\n";
		} catch(ClassCastException e) {
			// at least one property is invalid 
			error = true;
			errorMsg += "at least one of your configuration values is invalid. please correct it and save again. " + e.getMessage() + "\n";
		}
		// done
		HTMLNode messageDiv = new HTMLNode("div").addChild("b");
		HTMLNode fontNode;
		if(error) {
			for(String curErrorMsg : errorMsg.split("\n")) {
				fontNode = new HTMLNode("font", curErrorMsg);
				fontNode.addAttribute("color", "red");
				messageDiv.addChild(fontNode);
				messageDiv.addChild(new HTMLNode("br"));
			}
		} else {
			fontNode = new HTMLNode("font", "Configuration saved.");
			fontNode.addAttribute("color", "green");
			messageDiv.addChild(fontNode);
			if(mStorage.config.firstStart) {
				messageDiv.addChild("br");
				messageDiv.addChild("br");
				messageDiv.addChild(new HTMLNode("span", "You can now "));
				fontNode = new HTMLNode("a", "start chatting");
				fontNode.addAttribute("href", "channelWindow");
				messageDiv.addChild(fontNode);
				messageDiv.addChild(new HTMLNode("span", "."));
			}
			mStorage.config.firstStart = false;
		}
		configProps.setProperty("firstStart", mStorage.config.firstStart.toString());
		try {
			out = new FileOutputStream("FLIC/config", false);
			configProps.store(out, "configuration for FLIC " + mStorage.config.version_major + "." + mStorage.config.version_minor + "." + mStorage.config.version_release + " created at the configuration page.");
			out.close();
		} catch (Exception e) {
			// ignore
		}
		return createConfig(mPageNode, messageDiv);
	}
	
	private PageNode createRoot(HTTPRequest req, ToadletContext ctx) { 
		return createStats(req, ctx);
		//return ctx.getPageMaker().getPageNode("Hello stranger. Welcome to FLIC", true, ctx);
//		PageNode mPageNode;
//		if(mStorage.config.firstStart) {
//			mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to FLIC", true, ctx);
//			return createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
//		} else {
//			// FIXME: make it configurable which page to show here
//			String channel = "#test";
//			if(!mStorage.config.autojoinChannel && !mStorage.config.joinedChannels.contains(channel) && mStorage.config.joinedChannels.size() > 0) {
//				channel =  mStorage.config.joinedChannels.get(0);
//			}
//			mPageNode = ctx.getPageMaker().getPageNode("FLIC " + channel, true, ctx);
//			createChannelWindow(mPageNode, channel);
//			return mPageNode;
//		}
	}
	private PageNode createConfig(PageNode mPageNode) {
		return createConfig(mPageNode, null);
	}
	private PageNode createConfig(PageNode mPageNode, HTMLNode additionalMessage) {
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("id", "FLIC");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		if(additionalMessage != null) {
			ownContentNode.addChild("br");
			ownContentNode.addChild(additionalMessage);
			ownContentNode.addChild("br");
		}
		ownContentNode.addChild("br");
		mPageNode.content.addChild(ownContentNode);
		
		HTMLNode table = new HTMLNode("table");
		HTMLNode tmpTRnode = new HTMLNode("tr");
		HTMLNode tmpTDnode = new HTMLNode("th", "name");
		tmpTRnode.addChild(tmpTDnode);
		tmpTDnode = new HTMLNode("th", "value");
		tmpTRnode.addChild(tmpTDnode);
		tmpTDnode = new HTMLNode("th", "description");
		tmpTRnode.addChild(tmpTDnode);
		table.addChild(tmpTRnode);
		table.addChild(addConfigTR("nick", "nick", 16, "fubar", "nick length must be < 16"));
		
		HTMLNode input = new HTMLNode("input");
		input.addAttribute("type", "submit");
		input.addAttribute("value", "save");
		
		HTMLNode mForm = new HTMLNode("form");
		mForm.addAttribute("action", "receiver");
		mForm.addAttribute("method", "POST");
		mForm.addChild(table);
		mForm.addChild(input);
		ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("width", "100%");
		ownContentNode.addAttribute("height", "100%");
		ownContentNode.addAttribute("align", "center");
		ownContentNode.addChild(mForm);
		mPageNode.content.addChild(ownContentNode);
		return mPageNode;
	}
	private HTMLNode addConfigTR(String name, String fieldName, int fieldSize, String value, String description) {
		HTMLNode tmpTRnode = new HTMLNode("tr");
		HTMLNode tmpTDnode = new HTMLNode("td", name + ":");
		tmpTRnode.addChild(tmpTDnode);
		HTMLNode input = new HTMLNode("input");
		input.addAttribute("type", "text");
		input.addAttribute("name", fieldName);
		input.addAttribute("size", Integer.toString(fieldSize));
		input.addAttribute("value", value);
		tmpTDnode = new HTMLNode("td");
		tmpTDnode.addChild(input);
		tmpTRnode.addChild(tmpTDnode);
		tmpTDnode = new HTMLNode("td", description);
		tmpTRnode.addChild(tmpTDnode);
		return tmpTRnode;
		
	}
	
	private PageNode createStats(HTTPRequest req, ToadletContext ctx) {
		PageNode mPageNode = ctx.getPageMaker().getPageNode("FLIC statistics", true, ctx);
		
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("id", "FLIC");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		ownContentNode.addChild("br");
		HTMLNode tableNode = new HTMLNode("table");
		tableNode = createStats_FetchOverview(tableNode);
		ownContentNode.addChild(tableNode);
		ownContentNode.addChild(new HTMLNode("br"));
		tableNode = new HTMLNode("table");
		tableNode = createStats_QueueOverview(tableNode);
		ownContentNode.addChild(tableNode);
		ownContentNode.addChild(new HTMLNode("br"));
		tableNode = new HTMLNode("table");
		tableNode = createStats_ChannelList(tableNode);
		ownContentNode.addChild(tableNode);
		ownContentNode.addChild(new HTMLNode("br"));
		tableNode = new HTMLNode("table");
		tableNode = createStats_UserList(tableNode);
		ownContentNode.addChild(tableNode);
		mPageNode.content.addChild(ownContentNode);
		return mPageNode;
	}
	
	private HTMLNode createStats_QueueOverview(HTMLNode table) {
		HTMLNode tmpTrNode = new HTMLNode("tr");
		HTMLNode tmpTdNode;
		// header
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.addAttribute("colspan", "3");
		tmpTdNode.setContent("task processing");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// description
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("td");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th", "waiting");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th", "processing time");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// content
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th", "messageParser");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", Integer.toString(mPtrWorker.getMessageParser().getCurrentQueueSize()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", String.format("%.8f", mPtrWorker.getMessageParser().getProcessingTime()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th", "insertQueue");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", Integer.toString(mPtrWorker.getCurrentQueueSize()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", String.format("%.8f", mPtrWorker.getProcessingTime()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th", "FLICeventQueue");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", Integer.toString(mPtrWorker.getFCPParser().getCurrentFLICeventsQueueSize()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", String.format("%.8f", mPtrWorker.getFCPParser().getFLICeventsProcessingTime()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th", "FCPcommandQueue");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", Integer.toString(mPtrWorker.getFCPParser().getCurrentFCPclientCommandsQueueSize()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", String.format("%.8f", mPtrWorker.getFCPParser().getFCPclientCommandsProcessingTime()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		
		return table;
	}

	private HTMLNode createStats_UserList(HTMLNode table) {
		// TODO: additionally save ddos and valid per user
		SimpleDateFormat sdf = new SimpleDateFormat("z HH:mm:ss");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		HTMLNode tmpTrNode;
		HTMLNode tmpTdNode;
		User user;
		// header
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.addAttribute("colspan", "8");
		tmpTdNode.setContent("user statistics");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// description
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("current nick");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("channels joined");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("channel messages");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("last\nmessageindex");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("ddos/invalid messages");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("ddos/invalid identities");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("failed messages");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("last activity");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		try {
			for(String ident : mStorage.knownIdents) {
				user = mStorage.userMap.get(ident);
				// values
				tmpTrNode = new HTMLNode("tr");
				tmpTdNode = new HTMLNode("td");
				HTMLNode tmpANode = new HTMLNode("a",basicHTMLencode(user.nick));
				//tmpANode.addAttribute("href", "../" + ident.replace("SSK", "USK") + mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Identity/" + user.identEdition);
				tmpANode.addAttribute("href", "profile?ident=" + ident);
				tmpTdNode.addChild(tmpANode);
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Integer.toString(user.channelCount));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Integer.toString(user.messageCount));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Long.toString(user.messageEditionHint));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Long.toString(user.message_ddos));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Long.toString(user.identity_ddos));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Integer.toString(user.failedMessageRequests.size()));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.addAttribute("nowrap", "nowrap");
				tmpTdNode.setContent(sdf.format(user.lastActivity));
				tmpTrNode.addChild(tmpTdNode);
				table.addChild(tmpTrNode);
			}
			return table;
		} catch (ConcurrentModificationException e) {
			// recursion ftw.. as long as there are not hundreds of new idents added at the same time
			// FIXME: this sucks
			return createStats_UserList( new HTMLNode("table"));
		}
	}
	private HTMLNode createStats_ChannelList(HTMLNode table) {
		SimpleDateFormat sdf = new SimpleDateFormat("z HH:mm:ss");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		HTMLNode tmpTrNode;
		HTMLNode tmpTdNode;
		// header
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.addAttribute("colspan", "4");
		tmpTdNode.setContent("channel statistics");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// description
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("channel");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("current users");
		tmpTrNode.addChild(tmpTdNode);
		//tmpTdNode = new HTMLNode("th");
		//tmpTdNode.setContent("messages");
		//tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("last message activity");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("topic");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		
		for(Channel chan : mStorage.channelList) {
			// values
			tmpTrNode = new HTMLNode("tr");
			tmpTdNode = new HTMLNode("td");
			HTMLNode tmpAnode = new HTMLNode("a",chan.name);
			tmpAnode.addAttribute("href", "changeToChannel?channel=" + chan.name.replace("#", ""));
			tmpTdNode.addChild(tmpAnode);
			tmpTrNode.addChild(tmpTdNode);
			tmpTdNode = new HTMLNode("td");
			tmpTdNode.setContent(Integer.toString(chan.currentUserCount));
			tmpTdNode.addAttribute("align", "right");
			tmpTrNode.addChild(tmpTdNode);
			//tmpTdNode = new HTMLNode("td");
			//tmpTdNode.setContent(Integer.toString(chan.lastMessageIndex +1));
			//tmpTdNode.addAttribute("align", "right");
			//tmpTrNode.addChild(tmpTdNode);
			tmpTdNode = new HTMLNode("td");
			tmpTdNode.setContent(sdf.format(chan.lastMessageActivity));
			tmpTrNode.addChild(tmpTdNode);
			tmpTdNode = new HTMLNode("td", chan.topic);
			tmpTrNode.addChild(tmpTdNode);
			table.addChild(tmpTrNode);
		}
		return table;
	}
	private HTMLNode createStats_FetchOverview(HTMLNode table) {
		// header indexChainStructure
		HTMLNode tmpTrNode = new HTMLNode("tr");
		HTMLNode tmpTdNode = new HTMLNode("th");
		tmpTdNode.addAttribute("colspan", "6");
		tmpTdNode.setContent("global statistics");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// header table
		tmpTrNode = new HTMLNode("tr");
		tmpTrNode.addChild("td", "");
		tmpTrNode.addChild("th", "valid");
		tmpTrNode.addChild("th", "new");
		tmpTrNode.addChild("th", "ddos");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("concurrent fetchers");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("USK subscriptions");
		tmpTrNode.addChild(tmpTdNode);
		//tmpTrNode.addChild("th", "ddos old version");
		//tmpTrNode.addChild("th", "ddos unknown version");
		//tmpTrNode.addChild("th", "count");
		table.addChild(tmpTrNode);
		tmpTrNode = new HTMLNode("tr");
		tmpTrNode.addChild("th", "Announce");
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.announce_valid));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.announce_valid - mStorage.announce_duplicate));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.announce_ddos));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getAnnounceFetcher().concurrentFetchCount));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent("-");
		tmpTrNode.addChild(tmpTdNode);
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosOldVersion));
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosUnknownVersion));
		table.addChild(tmpTrNode);
		tmpTrNode = new HTMLNode("tr");
		tmpTrNode.addChild("th", "Identity");
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.identity_valid));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent("-");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.identity_ddos));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getIdentityFetcher().concurrentFetchCount));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getIdentityFetcher().getSubscriptionCount()));
		tmpTrNode.addChild(tmpTdNode);
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosOldVersion));
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosUnknownVersion));
		table.addChild(tmpTrNode);
		tmpTrNode = new HTMLNode("tr");
		tmpTrNode.addChild("th", "Message");
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.message_valid));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent("-");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.message_ddos));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getMessageFetcher().concurrentFetchCount));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getMessageFetcher().getSubscriptionCount()));
		tmpTrNode.addChild(tmpTdNode);
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosOldVersion));
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosUnknownVersion));
		table.addChild(tmpTrNode);
		
		//tmpTrNode.addChild("td", Integer.toString(mStorage.messageCount));
		return table;
	}
	
	private PageNode createRequestInfo(HTTPRequest req, ToadletContext ctx) {
		return createRequestInfo(req, ctx, false);
	}
	private PageNode createRequestInfo(HTTPRequest req, ToadletContext ctx, boolean isNotImplemented) { 
		URI uri = ctx.getUri();
		PageNode mPageNode = ctx.getPageMaker().getPageNode("FLIC InfoPage", true, ctx);
		mPageNode.content.addChild("br");
		if(isNotImplemented) {
			mPageNode.content.addChild("span","You reached this page because FLIC was not in the mood to implement this feature.");
		} else {
			mPageNode.content.addChild("span","You reached this page because FLIC did not found the requested URI");
		}
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("br");
		// requested URI
		mPageNode.content.addChild("b", "URI:");
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("i", uri.toString());
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("br");
		// used Method
		mPageNode.content.addChild("b", "Method:");
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("i", req.getMethod());
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("br");
		// POST data
		mPageNode.content.addChild("b", "HTTPRequest.getParts()-->HTTPRequest.getPartAsStringFailsafe(part, 255):");
		mPageNode.content.addChild("br");
		String tmpGetRequestParts[] = req.getParts();
		for (int i = 0; i < tmpGetRequestParts.length; i++) {
			mPageNode.content.addChild("i", tmpGetRequestParts[i] + "-->" + req.getPartAsStringFailsafe(tmpGetRequestParts[i], 255));
//			mPageNode.content.addChild("i", tmpGetRequestParts[i] + "-->");	
//			mPageNode.content.addChild("br");
//			for(String part : req.getMultipleParam(tmpGetRequestParts[i])) {
//				mPageNode.content.addChild("i", part);	
//				mPageNode.content.addChild("br");
//			}
				 //+ req.getPartAsStringFailsafe(tmpGetRequestParts[i], 255));
			mPageNode.content.addChild("br");
		}
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("br");
		// Parameters Key-->Value
		mPageNode.content.addChild("b", "HTTPRequest.getParameterNames()-->HTTPRequest.getParam(parameter):");
		mPageNode.content.addChild("br");
		String partString = "";
		Collection<String> tmpGetRequestParameterNames = req.getParameterNames();
		for (Iterator<String> tmpIterator = tmpGetRequestParameterNames.iterator(); tmpIterator.hasNext();) {
			partString = tmpIterator.next();
			mPageNode.content.addChild("i", partString + "-->" + req.getParam(partString));
			mPageNode.content.addChild("br");
		}
		return mPageNode;
	}
}
