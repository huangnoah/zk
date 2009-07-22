/* WpdExtendlet.java

	Purpose:
		
	Description:
		
	History:
		Mon Oct  6 10:47:11     2008, Created by tomyeh

Copyright (C) 2008 Potix Corporation. All Rights Reserved.

	This program is distributed under GPL Version 3.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
*/
package org.zkoss.zk.ui.http;

import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.zkoss.lang.Strings;
import org.zkoss.lang.Exceptions;
import org.zkoss.io.Files;
import org.zkoss.idom.Element;
import org.zkoss.idom.input.SAXBuilder;
import org.zkoss.idom.util.IDOMs;

import org.zkoss.web.servlet.Servlets;
import org.zkoss.web.servlet.http.Https;
import org.zkoss.web.servlet.http.Encodes;
import org.zkoss.web.util.resource.ExtendletContext;
import org.zkoss.web.util.resource.ExtendletConfig;
import org.zkoss.web.util.resource.ExtendletLoader;

import org.zkoss.zk.ui.WebApps;
import org.zkoss.zk.ui.WebApp;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.metainfo.LanguageDefinition;
import org.zkoss.zk.ui.metainfo.WidgetDefinition;
import org.zkoss.zk.ui.util.Configuration;

/**
 * The extendlet to handle WPD (Widget Package Descriptor).
 *
 * <p>Note: it assumes all JavaScript files are encoded in UTF-8.
 *
 * @author tomyeh
 * @since 5.0.0
 */
public class WpdExtendlet extends AbstractExtendlet {
	public void init(ExtendletConfig config) {
		init(config, new WpdLoader());
	}

	public void service(HttpServletRequest request,
	HttpServletResponse response, String path, String extra)
	throws ServletException, IOException {
		byte[] data;
		setProvider(new Provider(request, response));
		try {
			final Object rawdata = _cache.get(path);
			if (rawdata == null) {
				if (Servlets.isIncluded(request))
					log.error("Failed to load the resource: "+path);
					//It might be eaten, so log the error
				response.sendError(response.SC_NOT_FOUND, path);
				return;
			}

			data = rawdata instanceof byte[] ? (byte[])rawdata:
				((WpdContent)rawdata).toByteArray();
		} finally {
			setProvider(null);
		}

		response.setContentType("text/javascript;charset=UTF-8");
		if (data.length > 200) {
			byte[] bs = Https.gzip(request, response, null, data);
			if (bs != null) data = bs; //yes, browser support compress
		}
		response.setContentLength(data.length);
		response.getOutputStream().write(data);
		response.flushBuffer();
	}
	/*package*/
	Object parse(InputStream is, String path) throws Exception {
		final Element root = new SAXBuilder(true, false, true).build(is).getRootElement();
		final String name = IDOMs.getRequiredAttributeValue(root, "name");
		if (name.length() == 0)
			throw new UiException("The name attribute must be specified, "+root.getLocator());
		final boolean zk = "zk".equals(name);
		final String lang = root.getAttributeValue("language");
		final LanguageDefinition langdef = //optional
			lang != null ? LanguageDefinition.lookup(lang): null;
		final String dir = path.substring(0, path.lastIndexOf('/') + 1);
		final WpdContent wc =
			"false".equals(root.getAttributeValue("cacheable")) ?
				new WpdContent(dir): null;

		final ByteArrayOutputStream out = new ByteArrayOutputStream(1024*8);
		String depends = null;
		if (zk) {
			write(out, "//ZK, Copyright (C) 2009 Potix Corporation. Distributed under GPL 3.0\n"
				+ "//jQuery, Copyright (c) 2009 John Resig\n"
				+ "if(!window.zk){");//may be loaded multiple times because specified in lang.xml
		} else {
			depends = root.getAttributeValue("depends");
			if (depends != null && depends.length() == 0)
				depends = null;
			if (depends != null) {
				write(out, "zPkg.load('");
				write(out, depends);
				write(out, "',function(){var ");
			}
			write(out, "_z='");
			write(out, name);
			write(out, "';try{var _zkpk=zk.$package(_z,false);\n");
		}

		final Map moldInfos = new HashMap();
		for (Iterator it = root.getElements().iterator(); it.hasNext();) {
			final Element el = (Element)it.next();
			final String elnm = el.getName();
			if ("widget".equals(elnm)) {
				final String wgtnm = IDOMs.getRequiredAttributeValue(el, "name");
				final String jspath = wgtnm + ".js"; //eg: /js/zul/wgt/Div.js
				if (!writeResource(out, jspath, dir, false)) {
					log.error("Widget "+wgtnm+": "+jspath+" not found, "+el.getLocator());
					continue;
				}

				final String wgtflnm = name + "." + wgtnm;
				write(out, "zkreg(_zkwg=");
				write(out, zk ? "zk.": "_zkpk.");
				write(out, wgtnm);
				write(out, ",'");
				write(out, wgtflnm);
				write(out, '\'');
				WidgetDefinition wgtdef = langdef != null ? langdef.getWidgetDefinitionIfAny(wgtflnm): null;
				if (wgtdef != null && wgtdef.isBlankPreserved())
					write(out, ",true");
				write(out, ");");
				if (wgtdef == null)
					continue;

				try {
					boolean first = true;
					for (Iterator e = wgtdef.getMoldNames().iterator(); e.hasNext();) {
						final String mold = (String)e.next();
						final String uri = wgtdef.getMoldURI(mold);
						if (uri == null) continue;

						if (first) {
							first = false;
							write(out, "_zkmd={};\n");
						}
							
						write(out, "_zkmd['");
						write(out, mold);
						write(out, "']=");

						String[] info = (String[])moldInfos.get(uri);
						if (info != null) { //reuse
							write(out, "[_zkpk.");
							write(out, info[0]);
							write(out, ",'");
							write(out, info[1]);
							write(out, "'];");
						} else {
							moldInfos.put(uri, new String[] {wgtnm, mold});
							if (!writeResource(out, uri, dir, true)) {
								write(out, "zk.$void;zk.error('");
								write(out, uri);
								write(out, " not found')");
								log.error("Failed to load mold "+mold+" for widget "+wgtflnm+": "+uri+" not found");
							}
							write(out, ';');
						}
					}
					if (!first) write(out, "zkmld(_zkwg,_zkmd);");
				} catch (Throwable ex) {
					log.error("Failed to load molds for widget "+wgtflnm+".\nCause: "+Exceptions.getMessage(ex));
				}
			} else if ("script".equals(elnm)) {
				String browser = el.getAttributeValue("browser");
				if (browser != null && wc == null)
					log.error("browser attribute not called in a cachable WPD, "+el.getLocator());
				String jspath = el.getAttributeValue("src");
				if (jspath != null && jspath.length() > 0) {
					if (wc != null
					&& (browser != null || jspath.indexOf('*') >= 0)) {
						move(wc, out);
						wc.add(jspath, browser);
					} else {
						if (browser != null) {
							final Provider provider = getProvider();
							if (provider != null && !Servlets.isBrowser(provider.request, browser))
								continue;
						}
						if (!writeResource(out, jspath, dir, true))
							log.error(jspath+" not found, "+el.getLocator());
					}
				}

				String s = el.getText(true);
				if (s != null && s.length() > 0) {
					write(out, s);
					write(out, '\n'); //might terminate with //
				}
			} else if ("function".equals(elnm)) {
				final MethodInfo mtd = getMethodInfo(el);
				if (mtd != null)
					if (wc != null) {
						move(wc, out);
						wc.add(mtd);
					} else {
						write(out, mtd);
					}
			} else {
				log.warning("Unknown element "+elnm+", "+el.getLocator());
			}
		}
		if (zk) {
			final WebApp wapp = getWebApp();
			if (wapp != null)
				writeAppInfo(out, wapp);
			write(out, '}'); //end of if
		} else {
			write(out, "\n}finally{zPkg.end(_z);}");
			if (depends != null) {
				write(out, "});zPkg.end('");
				write(out, name);
				write(out, "',1);");
			}
		}

		if (wc != null) {
			move(wc, out);
			return wc;
		}
		return out.toByteArray();
	}
	private boolean writeResource(OutputStream out, String path,
	String dir, boolean locate)
	throws IOException, ServletException {
		if (path.startsWith("~./")) path = path.substring(2);
		else if (path.charAt(0) != '/')
			path = Files.normalize(dir, path);

		final InputStream is =
			(getProvider()).getResourceAsStream(path, locate);
		if (is == null) {
			write(out, "zk.log('");
			write(out, path);
			write(out, " not found');");
			return false;
		}

		Files.copy(out, is);
		write(out, '\n'); //might terminate with //
		return true;
	}
	private void write(OutputStream out, String s) throws IOException {
		if (s != null) {
			final byte[] bs = s.getBytes("UTF-8");
			out.write(bs, 0, bs.length);
		}
	}
	/*private static void write(OutputStream out, StringBuffer sb)
	throws IOException {
		final byte[] bs = sb.toString().getBytes("UTF-8");
		out.write(bs, 0, bs.length);
		sb.setLength(0);
	}*/
	private void writeln(OutputStream out) throws IOException {
		write(out, '\n');
	}
	private void write(OutputStream out, char cc) throws IOException {
		assert cc < 128;
		final byte[] bs = new byte[] {(byte)cc};
		out.write(bs, 0, 1);
	}
	private void write(OutputStream out, MethodInfo mtd) throws IOException {
		try {
			write(out, invoke(mtd));
		} catch (IOException ex) {
			throw ex;
		}
	}
	private void move(WpdContent wc, ByteArrayOutputStream out) {
		final byte[] bs = out.toByteArray();
		if (bs.length > 0) {
			wc.add(bs);
			out.reset();
		}
	}

	private void writeAppInfo(OutputStream out, WebApp wapp)
	throws IOException, ServletException {
		final StringBuffer sb = new StringBuffer(256);
		sb.append("\nzkver('").append(wapp.getVersion())
			.append("','").append(wapp.getBuild());
		final Provider provider = getProvider();
		if (provider != null) {
			final ServletContext ctx = getServletContext();
			String s = Encodes.encodeURL(ctx, provider.request, provider.response, "/");
			int j = s.lastIndexOf('/'); //might have jsessionid=...
			if (j >= 0) s = s.substring(0, j) + s.substring(j + 1);

			sb.append("','")
				.append(s)
				.append("','")
				.append(Encodes.encodeURL(ctx, provider.request, provider.response,
					wapp.getUpdateURI(false)))
				.append('\'');
		} else
			sb.append("','',''");

		for (Iterator it = LanguageDefinition.getByDeviceType("ajax").iterator();
		it.hasNext();) {
			final LanguageDefinition langdef = (LanguageDefinition)it.next();
			final Set mods = langdef.getJavaScriptModules().entrySet();
			if (!mods.isEmpty())
				for (Iterator e = mods.iterator(); e.hasNext();) {
					final Map.Entry me = (Map.Entry)e.next();
					sb.append(",'").append(me.getKey())
					  .append("','").append(me.getValue()).append('\'');
				}
		}

		sb.append(");");
		final int jdot = sb.length();

		if (WebApps.getFeature("enterprise"))
			sb.append(",ed:'e'");
		else if (WebApps.getFeature("professional"))
			sb.append(",ed:'p'");

		final Configuration config = wapp.getConfiguration();
		int v = config.getProcessingPromptDelay();
		if (v != 900) sb.append(",pd:").append(v);
		v = config.getTooltipDelay();
		if (v != 800) sb.append(",td:").append(v);
		v = config.getResendDelay();
		if (v >= 0) sb.append(",rd:").append(v);
		v = config.getClickFilterDelay();
		if (v >= 0) sb.append(",cd:").append(v);
		if (config.isDebugJS()) sb.append(",dj:1");
		if (config.isKeepDesktopAcrossVisits())
			sb.append(",kd:1");
		if (config.getPerformanceMeter() != null)
			sb.append(",pf:1");
		if (sb.length() > jdot) {
			sb.replace(jdot, jdot + 1, "zkopt({");
			sb.append("});\n");
		}

		final int[] cers = config.getClientErrorReloadCodes();
		if (cers.length > 0) {
			final int k = sb.length();
			for (int j = 0; j < cers.length; ++j) {
				final String uri = config.getClientErrorReload(cers[j]);
				if (uri != null) {
					if (k != sb.length()) sb.append(',');
					sb.append(cers[j]).append(",'")
						.append(Strings.escape(uri, Strings.ESCAPE_JAVASCRIPT))
						.append('\'');
				}
			}
			if (k != sb.length()) {
				sb.insert(k, "zAu.setErrorURI(");
				sb.append(");\n");
			}
		}
		write(out, sb.toString());
	}

	private class WpdLoader extends ExtendletLoader {
		private WpdLoader() {
		}

		//-- super --//
		protected Object parse(InputStream is, String path) throws Exception {
			return WpdExtendlet.this.parse(is, path);
		}
		protected ExtendletContext getExtendletContext() {
			return _webctx;
		}
	}
	/*package*/ class WpdContent {
		private final String _dir;
		private final List _cnt = new LinkedList();
		private WpdContent(String dir) {
			_dir = dir;
		}
		private void add(byte[] bs) {
			_cnt.add(bs);
		}
		private void add(MethodInfo mtd) {
			_cnt.add(mtd);
		}
		private void add(String jspath, String browser) {
			_cnt.add(new String[] {jspath, browser});
		}
		/*package*/ byte[] toByteArray() throws ServletException, IOException {
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (Iterator it = _cnt.iterator(); it.hasNext();) {
				final Object o = it.next();
				if (o instanceof byte[])
					out.write((byte[])o);
				else if (o instanceof MethodInfo)
					write(out, (MethodInfo)o);
				else {
					final String[] inf = (String[])o;
					if (inf[1] != null) {
						final Provider provider = getProvider();
						if (provider != null && !Servlets.isBrowser(provider.request, inf[1]))
							continue;
					}
					if (!writeResource(out, inf[0], _dir, true))
						log.error(inf[0] + " not found");
				}
			}
			return out.toByteArray();
		}
	}
}
