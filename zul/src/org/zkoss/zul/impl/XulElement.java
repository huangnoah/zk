/* XulElement.java

{{IS_NOTE
	Purpose:
		
	Description:
		
	History:
		Mon Jun 20 16:01:40     2005, Created by tomyeh
}}IS_NOTE

Copyright (C) 2005 Potix Corporation. All Rights Reserved.

{{IS_RIGHT
	This program is distributed under GPL Version 2.0 in the hope that
	it will be useful, but WITHOUT ANY WARRANTY.
}}IS_RIGHT
*/
package org.zkoss.zul.impl;

import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;

import org.zkoss.lang.Objects;
import org.zkoss.lang.Strings;
import org.zkoss.xml.HTMLs;

import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.HtmlBasedComponent;
import org.zkoss.zk.ui.WrongValueException;
import org.zkoss.zk.ui.ComponentNotFoundException;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.au.Command;
import org.zkoss.zul.au.impl.ColSizeCommand;
import org.zkoss.zul.au.impl.PagingCommand;
import org.zkoss.zul.event.ZulEvents;

/**
 * The fundamental class for XUL elements.
 *
 * @author tomyeh
 */
abstract public class XulElement extends HtmlBasedComponent {
	static {
		//register commands
		new ColSizeCommand(ZulEvents.ON_COL_SIZE, 0);
			//Don't use Command.IGNORE_OLD_EQUIV since users might drag diff borders
		new PagingCommand(ZulEvents.ON_PAGING, Command.SKIP_IF_EVER_ERROR);
	}

	/** The popup ID that will be shown when click. */
	private String _popup;
	/** The context ID that will be shown when right-click. */
	private String _ctx;
	/** The tooltip ID that will be shown when mouse-over. */
	private String _tooltip;
	/** The action. */
	private String _action;
	/** The parsed actions. */
	private Map _actmap;

	/** Returns the ID of {@link org.zkoss.zul.Popup} that should appear
	 * when the user right-clicks on the element (aka., context menu).
	 *
	 * <p>Default: null (no context menu).
	 */
	public String getContext() {
		return _ctx;
	}
	/** Sets the ID of {@link org.zkoss.zul.Popup} that should appear
	 * when the user right-clicks on the element (aka., context menu).
	 *
	 * <p>An onOpen event is sent to the context menu if it is going to
	 * appear. Therefore, developers can manipulate it dynamically
	 * (perhaps based on OpenEvent.getReference) by listening to the onOpen
	 * event.
	 */
	public void setContext(String context) {
		if (!Objects.equals(_ctx, context)) {
			_ctx = context;
			smartUpdate("z.ctx", _ctx);
		}
	}
	/** Returns the ID of {@link org.zkoss.zul.Popup} that should appear
	 * when the user clicks on the element.
	 *
	 * <p>Default: null (no poppup).
	 */
	public String getPopup() {
		return _popup;
	}
	/** Sets the ID of {@link org.zkoss.zul.Popup} that should appear
	 * when the user clicks on the element.
	 *
	 * <p>An onOpen event is sent to the popup menu if it is going to
	 * appear. Therefore, developers can manipulate it dynamically
	 * (perhaps based on OpenEvent.getReference) by listening to the onOpen
	 * event.
	 */
	public void setPopup(String popup) {
		if (!Objects.equals(_popup, popup)) {
			_popup = popup;
			smartUpdate("z.pop", _popup);
		}
	}
	/** Returns the ID of {@link org.zkoss.zul.Popup} that should be used
	 * as a tooltip window when the mouse hovers over the element for a moment.
	 * The tooltip will automatically disappear when the mouse is moved.
	 *
	 * <p>Default: null (no tooltip).
	 */
	public String getTooltip() {
		return _tooltip;
	}
	/** Sets the ID of {@link org.zkoss.zul.Popup} that should be used
	 * as a tooltip window when the mouse hovers over the element for a moment.
	 *
	 * <p>An onOpen event is sent to the tooltip if it is going to
	 * appear. Therefore, developers can manipulate it dynamically
	 * (perhaps based on OpenEvent.getReference) by listening to the onOpen
	 * event.
	 */
	public void setTooltip(String tooltip) {
		if (!Objects.equals(_tooltip, tooltip)) {
			_tooltip = tooltip;
			smartUpdate("z.tip", _tooltip);
		}
	}

	/** Returns the client-side action (CSA).
	 * <p>The format: <br>
	 * action1: javascript1; javascript2; action2: javascript3
	 *
	 * <p>You could specify any action as long as JavaScript supports,
	 * such as onfocus, onblur, onmouseover and onmousout.
	 */
	public String getAction() {
		return _action;
	}
	/** Sets the client-side action.
	 */
	public void setAction(String action) {
		if (action != null && action.length() == 0) action = null;
		if (!Objects.equals(_action, action)) {
			final Map old = _actmap;

			_actmap = action != null ? parseAction(action): null;
				//do it first because parseAction might fail
			_action = action;

			if (getPage() != null) {
				if (_actmap != null)
					for (Iterator it = _actmap.entrySet().iterator();
					it.hasNext();) {
						final Map.Entry me = (Map.Entry)it.next();
						final String actnm = (String)me.getKey();
						final String actval = (String)me.getValue();
						if (old == null
						|| !Objects.equals(actval, old.get(actnm)))
							smartUpdate(actnm, toJavaScript(actval));
					}
				if (old != null)
					for (Iterator it = old.keySet().iterator(); it.hasNext();) {
						final String actnm = (String)it.next();
						if (_actmap == null || !_actmap.containsKey(actnm))
							smartUpdate(actnm, null);
					}
			}
		}
	}

	/** Returns a map of actions (String name, String javascript).
	 */
	private static final Map parseAction(String action) {
		//1. Look for the first ':'
		final Map map = new HashMap();
		int k = action.indexOf(':');
		if (k < 0) throw new WrongValueException("Unknown action: "+action);

		int j = 0, len = action.length();
		for (;;) {
			final String actnm = action.substring(j, k).trim();
			if (actnm.length() == 0) throw new WrongValueException("Unknown action: "+action);

			//2. next ':'
			int l = len; //next ':'
			for (j = ++k; k < len; ++k) {
				final char cc = action.charAt(k);
				if (cc == '\'' || cc == '"') {
					while (++k < len) {
						final char c2 = action.charAt(k);
						if (c2 == cc) break;
						if (c2 == '\\') ++k;
					}
				} else if (cc == ';') {
					l = Strings.skipWhitespaces(action, k + 1);
					for (; l < len; ++l) {
						final char c2 = action.charAt(l);
						if (c2 < 'a' || c2 > 'z')
							break;
					}
					l = Strings.skipWhitespaces(action, l);
					if (l >= len || action.charAt(l) == ':') {
						++k;
						break; //found
					}
				}
			}

			//3. generate it
			final String val = action.substring(j, k > len ? len: k).trim();
			if (val.length() > 0) map.put(actnm, val);
			if (l >= len) return map; //done
			j = k;
			k = l;
		}
	}
	/** Converts an action to JavaScript by interpreting a fellow into
	 * 'uuid'
	 */
	private final String toJavaScript(String action) {
		if (action == null) return null;

		StringBuffer sb = null;
		for (int j = 0, len = action.length();;) {
			final int k = action.indexOf("#{", j);
			if (k < 0)
				return sb != null ?
					sb.append(action.substring(j)).toString(): action;

			final int l = action.indexOf('}', k + 2);
			if (l < 0)
				throw new WrongValueException("Illegal action: unclosed EL expression.\n"+action);

			if (sb == null) sb = new StringBuffer(len);
			sb.append(action.substring(j, k));

			//eval EL
			Object val = Executions.evaluate(this,
				'$' + action.substring(k + 1, l + 1), Object.class);
			if (val == null || (val instanceof Number)) {
				sb.append(val);
			} else if (val instanceof Component) {
				sb.append(" $e('")
					.append(Strings.escape(((Component)val).getUuid(), "'\\"))
					.append("')");
			} else if (val instanceof Date) {
				sb.append(" new Date(").append(((Date)val).getTime())
					.append(')');
			} else { //FUTURE: regex
				sb.append('\'')
					.append(Strings.escape(val.toString(), "'\\"))
					.append('\'');
			}

			//next
			j = l + 1;
		}
	}

	/** Returns the attributes for onClick, onRightClick and onDoubleClick
	 * by checking whether the corresponding listeners are added,
	 * or null if none is added.
	 *
	 * @param ignoreOnClick whether to ignore onClick
	 */
	protected String getAllOnClickAttrs(boolean ignoreOnClick) {
		StringBuffer sb = null;
		if (!ignoreOnClick) sb = appendAsapAttr(sb, Events.ON_CLICK);
		sb = appendAsapAttr(sb, Events.ON_DOUBLE_CLICK);
		sb = appendAsapAttr(sb, Events.ON_RIGHT_CLICK);
		return sb != null ? sb.toString():  null;
	}

	//Cloneable//
	public Object clone() {
		final XulElement clone = (XulElement)super.clone();
		if (clone._actmap != null)
			clone._actmap = new HashMap(clone._actmap);
		return clone;
	}

	//-- super --//
	public String getOuterAttrs() {
		final String attrs = super.getOuterAttrs();
		final String ctx = getContext(), popup = getPopup(), tip = getTooltip();
			//Let derives (e.g., treerow has a chance to override it)
		if (ctx == null &&  tip == null && popup == null)
			return attrs;

		final StringBuffer sb = new StringBuffer(80).append(attrs);
		HTMLs.appendAttribute(sb, "z.ctx", ctx);
		HTMLs.appendAttribute(sb, "z.pop", popup);
		HTMLs.appendAttribute(sb, "z.tip", tip);
		return sb.toString();
	}

	/** Generates the Client-Side-Action attributes to the interior tag.
	 * Reason: onfocus is the main use.
	 */
	public String getInnerAttrs() {
		final String attrs = super.getInnerAttrs();
		if (_actmap == null)
			return attrs;

		final StringBuffer sb = new StringBuffer(100).append(attrs);
		for (Iterator it = _actmap.entrySet().iterator(); it.hasNext();) {
			final Map.Entry me = (Map.Entry)it.next();
			HTMLs.appendAttribute(sb,
				(String)me.getKey(), toJavaScript((String)me.getValue()));
		}
		return sb.toString();
	}
}
