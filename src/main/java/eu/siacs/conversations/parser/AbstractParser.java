package eu.siacs.conversations.parser;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Stanza;

public abstract class AbstractParser {

	protected final XmppConnectionService mXmppConnectionService;
	protected final Account account;

	protected AbstractParser(final XmppConnectionService service, final Account account) {
		this.mXmppConnectionService = service;
		this.account = account;
	}

	public static Long parseTimestamp(Element element, Long d) {
		return parseTimestamp(element, d, false);
	}

	public static Long parseTimestamp(Element element, Long d, boolean ignoreCsiAndSm) {
		long min = Long.MAX_VALUE;
		boolean returnDefault = true;
		final Jid to;
		if (ignoreCsiAndSm && element instanceof Stanza stanza) {
			to = stanza.getTo();
		} else {
			to = null;
		}
		for (Element child : element.getChildren()) {
			if ("delay".equals(child.getName()) && "urn:xmpp:delay".equals(child.getNamespace())) {
				final Jid f =
						to == null
								? null
								: Jid.Invalid.getNullForInvalid(child.getAttributeAsJid("from"));
				if (f != null && (to.asBareJid().equals(f) || to.getDomain().equals(f))) {
					continue;
				}
				final String stamp = child.getAttribute("stamp");
				if (stamp != null) {
					try {
						min = Math.min(min, AbstractParser.parseTimestamp(stamp));
						returnDefault = false;
					} catch (Throwable t) {
						// ignore
					}
				}
			}
		}
		if (returnDefault) {
			return d;
		} else {
			return min;
		}
	}

	public static long parseTimestamp(Element element) {
		return parseTimestamp(element, System.currentTimeMillis());
	}

	// Formats tried in order. Formats without an explicit timezone token (Z/z) are parsed
	// as UTC to avoid device-locale-dependent results.
	private static final String[] TIMESTAMP_FORMATS = {
		"yyyy-MM-dd'T'HH:mm:ssZ",       // ISO 8601 / RFC 3339 / XEP-0082  e.g. 2024-01-15T10:30:00+0530
		"yyyy-MM-dd'T'HH:mm:ss",        // ISO 8601 without timezone        e.g. 2024-01-15T10:30:00
		"EEE, dd MMM yyyy HH:mm:ss Z",  // RFC 2822 with numeric offset      e.g. Mon, 15 Jan 2024 10:30:00 +0000
		"EEE, dd MMM yyyy HH:mm:ss z",  // RFC 2822 with named timezone      e.g. Mon, 15 Jan 2024 10:30:00 GMT
		"dd MMM yyyy HH:mm:ss Z",       // RFC 2822 without weekday          e.g. 15 Jan 2024 10:30:00 +0000
		"dd MMM yyyy HH:mm:ss z",       // RFC 2822 without weekday, named   e.g. 15 Jan 2024 10:30:00 GMT
		"yyyy-MM-dd HH:mm:ssZ",         // SQL-style with offset             e.g. 2024-01-15 10:30:00+0000
		"yyyy-MM-dd HH:mm:ss",          // SQL-style without timezone        e.g. 2024-01-15 10:30:00
		"yyyy-MM-dd",                   // date only                         e.g. 2024-01-15
	};

	/**
	 * Parses a timestamp string in any of the commonly used formats (ISO 8601 / RFC 3339,
	 * RFC 2822, XEP-0082, SQL-style, date-only). Handles arbitrary-precision fractional
	 * seconds and all numeric timezone offset forms (+HH:MM, +HHMM, Z).
	 */
	public static long parseTimestamp(final String raw) throws ParseException {
		if (raw == null) {
			throw new ParseException("null timestamp", 0);
		}
		final String normalized = normalizeTimezoneOffset(raw.trim());

		// ISO 8601 allows arbitrary-precision fractional seconds; SimpleDateFormat only handles
		// milliseconds. Strip the fraction, convert it to ms, and re-add it after parsing.
		long extraMillis = 0;
		String forParsing = normalized;
		if (normalized.length() > 19 && normalized.charAt(19) == '.') {
			final int tzStart = findTimezoneOffset(normalized);
			final String fraction = normalized.substring(19, tzStart);
			try {
				extraMillis = Math.round(Double.parseDouble("0" + fraction) * 1000);
			} catch (final NumberFormatException ignored) {}
			forParsing = normalized.substring(0, 19) + normalized.substring(tzStart);
		}

		for (final String format : TIMESTAMP_FORMATS) {
			try {
				final SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
				sdf.setLenient(false);
				// Formats without an explicit timezone token default to UTC.
				if (!format.endsWith("Z") && !format.endsWith("z")) {
					sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
				}
				final Date date = sdf.parse(forParsing);
				if (date != null) {
					return date.getTime() + extraMillis;
				}
			} catch (final ParseException ignored) {}
		}
		throw new ParseException("Unparseable timestamp: " + raw, 0);
	}

	/**
	 * Normalises the timezone suffix of a timestamp string:
	 *  Z           → +0000
	 *  +HH:MM      → +HHMM  (RFC 3339 colon form, any offset)
	 *  -HH:MM      → -HHMM
	 * All other forms are returned unchanged.
	 */
	private static String normalizeTimezoneOffset(final String timestamp) {
		if (timestamp.endsWith("Z")) {
			return timestamp.substring(0, timestamp.length() - 1) + "+0000";
		}
		final int len = timestamp.length();
		if (len >= 6) {
			final char sign = timestamp.charAt(len - 6);
			final char colon = timestamp.charAt(len - 3);
			if ((sign == '+' || sign == '-') && colon == ':') {
				return timestamp.substring(0, len - 3) + timestamp.substring(len - 2);
			}
		}
		return timestamp;
	}

	/**
	 * Returns the start index of a ±HHMM timezone suffix in a normalized timestamp,
	 * or the string length if no such suffix is present.
	 */
	private static int findTimezoneOffset(final String normalized) {
		final int len = normalized.length();
		if (len >= 5) {
			final char sign = normalized.charAt(len - 5);
			if (sign == '+' || sign == '-') {
				return len - 5;
			}
		}
		return len;
	}

	public static long getTimestamp(final String input) throws ParseException {
		if (input == null) {
			throw new IllegalArgumentException("timestamp should not be null");
		}
		return parseTimestamp(input);
	}

	protected void updateLastseen(final Account account, final Jid from) {
		final Contact contact = account.getRoster().getContact(from);
		contact.setLastResource(from.isBareJid() ? "" : from.getResource());
	}

	protected static String avatarData(Element items) {
		Element item = items.findChild("item");
		if (item == null) {
			return null;
		}
		return item.findChildContent("data", "urn:xmpp:avatar:data");
	}

	public static MucOptions.User parseItem(Conversation conference, Element item) {
		return parseItem(conference,item,null,null,null,new Element("hats", "urn:xmpp:hats:0"));
	}

	public static MucOptions.User parseItem(final Conversation conference, Element item, Jid fullJid, final Element occupantId, final String nicknameIn, final Element hatsEl) {
		final String local = conference.getJid().getLocal();
		final String domain = conference.getJid().getDomain().toString();
		String affiliation = item.getAttribute("affiliation");
		String role = item.getAttribute("role");
		String nick = item.getAttribute("nick");
		if (nick != null && fullJid == null) {
			try {
				fullJid = Jid.of(local, domain, nick);
			} catch (IllegalArgumentException e) {
				fullJid = null;
			}
		}
		Jid realJid = item.getAttributeAsJid("jid");
		if (fullJid != null) nick = fullJid.getResource();
		String nickname = null;
		if (nick != null && nicknameIn != null) nickname = nick.equals(nicknameIn) ? nick : null;
		try {
			if (nickname == null && nicknameIn != null && nick != null && gnu.inet.encoding.Punycode.decode(nick).equals(nicknameIn)) {
				nickname = nicknameIn;
			}
		} catch (final Exception e) { }
		Set<MucOptions.Hat> hats = new TreeSet<>();
        if (hatsEl != null) {
            for (final var hat : hatsEl.getChildren()) {
                if ("hat".equals(hat.getName()) && ("urn:xmpp:hats:0".equals(hat.getNamespace()) || "xmpp:prosody.im/protocol/hats:1".equals(hat.getNamespace()))) {
                    hats.add(new MucOptions.Hat(hat));
                }
            }
        }
		MucOptions.User user = new MucOptions.User(conference.getMucOptions(), fullJid, occupantId == null ? null : occupantId.getAttribute("id"), nickname, hatsEl == null ? null : hats);
		if (Jid.Invalid.isValid(realJid)) {
			user.setRealJid(realJid);
		}
		user.setAffiliation(affiliation);
		user.setRole(role);
		return user;
	}

	public static String extractErrorMessage(final Element packet) {
		final Element error = packet.findChild("error");
		if (error != null && error.getChildren().size() > 0) {
			final List<String> errorNames = orderedElementNames(error.getChildren());
			final String text = error.findChildContent("text");
			if (text != null && !text.trim().isEmpty()) {
				return prefixError(errorNames) + text;
			} else if (errorNames.size() > 0) {
				return prefixError(errorNames) + errorNames.get(0).replace("-", " ");
			}
		}
		return null;
	}

	public static String errorMessage(Element packet) {
		final Element error = packet.findChild("error");
		if (error != null && error.getChildren().size() > 0) {
			final List<String> errorNames = orderedElementNames(error.getChildren());
			final String text = error.findChildContent("text");
			if (text != null && !text.trim().isEmpty()) {
				return text;
			} else if (errorNames.size() > 0) {
				return errorNames.get(0).replace("-", " ");
			}
		}
		return null;
	}

	private static String prefixError(List<String> errorNames) {
		if (errorNames.size() > 0) {
			return errorNames.get(0) + '\u001f';
		}
		return "";
	}

	private static List<String> orderedElementNames(List<Element> children) {
		List<String> names = new ArrayList<>();
		for (Element child : children) {
			final String name = child.getName();
			if (name != null && !name.equals("text")) {
				if ("urn:ietf:params:xml:ns:xmpp-stanzas".equals(child.getNamespace())) {
					names.add(name);
				} else {
					names.add(0, name);
				}
			}
		}
		return names;
	}
}
