package jks.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The part of STUN (RFC 5389) this game speaks : a Binding request, and its success answer carrying
 * the address the request came from. Pure, like {@link Net_Codec} : no socket, no game.
 *
 * It is what NAT traversal is made of (phase 2.2, r42), all of it on the game's ONE socket :
 * <ul>
 * <li>asked of a public STUN server, the answer is this socket's mapping as the internet sees it, and
 *     asking two servers from the same socket says whether that mapping depends on where we send
 *     (RFC 5780) - the early warning ;</li>
 * <li>sent to another player's candidates, a request that is answered IS the connectivity check, and
 *     sending it is the punch.</li>
 * </ul>
 *
 * A packet is
 * <pre>
 *   type u16 | length u16 | magic cookie u32 0x2112A442 | transaction u96 | attributes, each type u16 | length u16 | value padded to 4
 *
 *   request    type 0x0001 | USERNAME 0x0006 (optional) | FINGERPRINT 0x8028
 *   success    type 0x0101 | XOR-MAPPED-ADDRESS 0x0020 | FINGERPRINT 0x8028
 * </pre>
 * big-endian. A STUN packet starts with 0x00 or 0x01 and carries the cookie at byte 4 : it cannot be a
 * game packet ({@link Net_Codec#VERSION} is 2 and climbs) or a lobby packet ('L'), and both codecs refuse
 * it as {@link Net_Rejected.Reason#NOT_OURS}.
 *
 * Decoding refuses a packet whole : truncated, a length that is not the packet's, a FINGERPRINT that does
 * not match, a request without one (a public server's answer may lack it, so there it is checked only when
 * present), an address that is not an IPv4 or IPv6 one. Attributes this game does not use are skipped,
 * because public servers add some (SOFTWARE, RESPONSE-ORIGIN, OTHER-ADDRESS). MESSAGE-INTEGRITY is not
 * spoken : the socket accepts bytes from anyone anyway, and nothing a check says is trusted beyond "this
 * address answered".
 */
public final class Stun_Codec
{
	public static final int MAGIC_COOKIE = 0x2112A442;
	public static final int BINDING_REQUEST = 0x0001, BINDING_SUCCESS = 0x0101;
	static final int MAPPED_ADDRESS = 0x0001, USERNAME = 0x0006, XOR_MAPPED_ADDRESS = 0x0020, FINGERPRINT = 0x8028;
	static final int FINGERPRINT_XOR = 0x5354554E;
	static final int HEADER = 20, TRANSACTION = 12;
	/** A USERNAME is an address here, so the lobby's limit is the codec's. */
	public static final int MAX_USERNAME = Lobby_Codec.MAX_ADDRESS;

	private Stun_Codec()
	{
	}

	/** One Binding request or success, decoded. */
	public static final class Binding
	{
		/** True for a success answer, false for a request. */
		public boolean success;
		/** Twelve bytes the asker chose : how an answer finds its question. */
		public final byte[] transaction = new byte[TRANSACTION];
		/** On a request between players : who is asking, as the lobby service sees them. Null otherwise. */
		public String username;
		/** On a success : where the request came from, in the transport's text. */
		public String mapped;

		public static Binding request(byte[] transaction, String username)
		{
			Binding binding = new Binding();
			System.arraycopy(transaction, 0, binding.transaction, 0, TRANSACTION);
			binding.username = username;
			return binding;
		}

		/** The answer to this request, as seen from this address. */
		public Binding answer(String from)
		{
			Binding binding = new Binding();
			binding.success = true;
			System.arraycopy(transaction, 0, binding.transaction, 0, TRANSACTION);
			binding.mapped = from;
			return binding;
		}
	}

	/** True when the packet starts like STUN : what a shared socket routes on before decoding anything. */
	public static boolean isStunPacket(ByteBuffer packet)
	{
		int at = packet.position();
		return packet.remaining() >= HEADER && (packet.get(at) & 0xC0) == 0 && packet.getInt(at + 4) == MAGIC_COOKIE;
	}

	public static ByteBuffer encode(Binding binding)
	{
		ByteBuffer out = ByteBuffer.allocate(Net_Transport.MAX_PAYLOAD).order(ByteOrder.BIG_ENDIAN);
		out.putShort((short) (binding.success ? BINDING_SUCCESS : BINDING_REQUEST));
		out.putShort((short) 0); // the length, once it is known
		out.putInt(MAGIC_COOKIE);
		out.put(binding.transaction);

		if (binding.username != null)
		{
			if (!Lobby_Codec.isAddress(binding.username) || binding.username.length() > MAX_USERNAME)
				throw new IllegalArgumentException("not a username this codec carries : " + binding.username);
			attribute(out, USERNAME, binding.username.length());
			for (int i = 0; i < binding.username.length(); i++)
				out.put((byte) binding.username.charAt(i));
			pad(out);
		}
		if (binding.success)
		{
			byte[] ip = ipOf(binding.mapped);
			if (ip == null)
				throw new IllegalArgumentException("not an ip:port this codec carries : " + binding.mapped);
			attribute(out, XOR_MAPPED_ADDRESS, 4 + ip.length);
			out.put((byte) 0);
			out.put((byte) (ip.length == 4 ? 1 : 2));
			out.putShort((short) (portOf(binding.mapped) ^ (MAGIC_COOKIE >>> 16)));
			xorIp(ip, binding.transaction);
			out.put(ip);
		}

		// FINGERPRINT last : its CRC covers the header with a length that already counts it
		out.putShort(2, (short) (out.position() - HEADER + 8));
		int crc = Net_Codec.crc32(out, 0, out.position());
		attribute(out, FINGERPRINT, 4);
		out.putInt(crc ^ FINGERPRINT_XOR);
		out.flip();
		return out;
	}

	/** Reads one whole packet, the buffer's remaining bytes. Consumes nothing and keeps no reference. */
	public static Binding decode(ByteBuffer packet) throws Net_Rejected
	{
		if (!isStunPacket(packet))
			throw new Net_Rejected(Net_Rejected.Reason.NOT_OURS, -1, "not a STUN packet");
		ByteBuffer in = packet.slice().order(ByteOrder.BIG_ENDIAN);
		int length = in.remaining();
		int declared = in.getShort(2) & 0xFFFF;
		if (declared != length - HEADER || declared % 4 != 0)
			throw new Net_Rejected(Net_Rejected.Reason.LENGTH, -1, "says " + declared + " B of attributes, carries " + (length - HEADER));

		Binding binding = new Binding();
		int type = in.getShort(0) & 0xFFFF;
		if (type == BINDING_SUCCESS)
			binding.success = true;
		else if (type != BINDING_REQUEST)
			throw new Net_Rejected(Net_Rejected.Reason.UNKNOWN_TYPE, -1, "STUN type 0x" + Integer.toHexString(type));
		in.position(8);
		in.get(binding.transaction);

		String xorMapped = null, mapped = null;
		boolean fingerprinted = false;
		while (in.remaining() > 0)
		{
			if (in.remaining() < 4)
				throw new Net_Rejected(Net_Rejected.Reason.LENGTH, -1, "an attribute header cut short");
			int at = in.position();
			int attribute = in.getShort() & 0xFFFF, size = in.getShort() & 0xFFFF;
			int padded = (size + 3) & ~3;
			if (padded > in.remaining())
				throw new Net_Rejected(Net_Rejected.Reason.LENGTH, -1, "attribute 0x" + Integer.toHexString(attribute) + " of " + size + " B past the end");
			int value = in.position();
			switch (attribute)
			{
				case USERNAME:
					if (size < 1 || size > MAX_USERNAME)
						throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "a username of " + size + " B");
					char[] chars = new char[size];
					for (int i = 0; i < size; i++)
						chars[i] = (char) (in.get(value + i) & 0xFF);
					binding.username = new String(chars);
					if (!Lobby_Codec.isAddress(binding.username))
						throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "a username that is not printable ASCII");
					break;
				case XOR_MAPPED_ADDRESS:
					xorMapped = address(in, value, size, binding.transaction, true);
					break;
				case MAPPED_ADDRESS:
					mapped = address(in, value, size, binding.transaction, false);
					break;
				case FINGERPRINT:
					if (size != 4 || value + 4 != length)
						throw new Net_Rejected(Net_Rejected.Reason.LENGTH, -1, "a FINGERPRINT that is not the last 4 B");
					if ((in.getInt(value) ^ FINGERPRINT_XOR) != Net_Codec.crc32(in, 0, at))
						throw new Net_Rejected(Net_Rejected.Reason.CHECKSUM, -1, length + " B");
					fingerprinted = true;
					break;
				default:
					break; // SOFTWARE, RESPONSE-ORIGIN, OTHER-ADDRESS : a server's, not ours to read
			}
			in.position(value + padded);
		}

		// Every request this game sends carries one ; a public server's answer may not, so there it is checked only when present
		if (!binding.success && !fingerprinted)
			throw new Net_Rejected(Net_Rejected.Reason.CHECKSUM, -1, "a request with no FINGERPRINT");
		if (binding.success)
		{
			binding.mapped = xorMapped != null ? xorMapped : mapped;
			if (binding.mapped == null)
				throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "a success with no mapped address");
		}
		return binding;
	}

	static void attribute(ByteBuffer out, int type, int size)
	{
		out.putShort((short) type);
		out.putShort((short) size);
	}

	static void pad(ByteBuffer out)
	{
		while (out.position() % 4 != 0)
			out.put((byte) 0);
	}

	static String address(ByteBuffer in, int at, int size, byte[] transaction, boolean xor) throws Net_Rejected
	{
		int family = in.get(at + 1) & 0xFF;
		int ipLength = family == 1 ? 4 : family == 2 ? 16 : -1;
		if (ipLength < 0 || size != 4 + ipLength)
			throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "an address of family " + family + " in " + size + " B");
		int port = in.getShort(at + 2) & 0xFFFF;
		byte[] ip = new byte[ipLength];
		for (int i = 0; i < ipLength; i++)
			ip[i] = in.get(at + 4 + i);
		if (xor)
		{
			port ^= MAGIC_COOKIE >>> 16;
			xorIp(ip, transaction);
		}
		return textOf(ip, port);
	}

	/** An IPv4 address is XORed with the cookie ; an IPv6 one with the cookie and then the transaction. */
	static void xorIp(byte[] ip, byte[] transaction)
	{
		for (int i = 0; i < ip.length; i++)
			ip[i] ^= i < 4 ? (byte) (MAGIC_COOKIE >>> (24 - 8 * i)) : transaction[i - 4];
	}

	// ---------------------------------------------------------------- the transport's text

	/**
	 * The text {@link Transport_Udp#textOf} gives the same endpoint : dotted IPv4, or IPv6 as eight
	 * unpadded lowercase groups in brackets, an IPv4-mapped IPv6 as the IPv4 it is. Written out rather
	 * than asked of InetAddress so nothing here can ever wait on a name lookup ; nettest holds the two
	 * to each other.
	 */
	public static String textOf(byte[] ip, int port)
	{
		StringBuilder text = new StringBuilder();
		boolean mapped = ip.length == 16 && isV4Mapped(ip);
		if (ip.length == 4 || mapped)
		{
			for (int i = ip.length - 4; i < ip.length; i++)
				text.append(i > ip.length - 4 ? "." : "").append(ip[i] & 0xFF);
		}
		else
		{
			text.append('[');
			for (int i = 0; i < 16; i += 2)
				text.append(i > 0 ? ":" : "").append(Integer.toHexString((ip[i] & 0xFF) << 8 | ip[i + 1] & 0xFF));
			text.append(']');
		}
		return text.append(':').append(port).toString();
	}

	static boolean isV4Mapped(byte[] ip)
	{
		for (int i = 0; i < 10; i++)
			if (ip[i] != 0)
				return false;
		return ip[10] == (byte) 0xFF && ip[11] == (byte) 0xFF;
	}

	/** The ip of an "ip:port" or "[ipv6]:port" literal, 4 or 16 bytes ; null for a name or anything else. Never looks a name up. */
	public static byte[] ipOf(String address)
	{
		int colon = address == null ? -1 : address.lastIndexOf(':');
		if (colon <= 0 || portOf(address) < 0)
			return null;
		String host = address.substring(0, colon);
		if (host.startsWith("[") && host.endsWith("]"))
			return ipv6Of(host.substring(1, host.length() - 1));
		String[] parts = host.split("\\.", -1);
		if (parts.length != 4)
			return null;
		byte[] ip = new byte[4];
		for (int i = 0; i < 4; i++)
		{
			int part = number(parts[i], 10, 3);
			if (part < 0 || part > 255)
				return null;
			ip[i] = (byte) part;
		}
		return ip;
	}

	/** The port of an "ip:port", or -1. */
	public static int portOf(String address)
	{
		int colon = address == null ? -1 : address.lastIndexOf(':');
		int port = colon < 0 ? -1 : number(address.substring(colon + 1), 10, 5);
		return port <= 0xFFFF ? port : -1;
	}

	static byte[] ipv6Of(String host)
	{
		if (host.indexOf('%') >= 0)
			host = host.substring(0, host.indexOf('%')); // a scope names an interface, not an address
		int gap = host.indexOf("::");
		String[] head = (gap < 0 ? host : host.substring(0, gap)).split(":", -1);
		String[] tail = gap < 0 ? new String[0] : host.substring(gap + 2).split(":", -1);
		if (gap >= 0 && head.length == 1 && head[0].isEmpty())
			head = new String[0];
		if (gap >= 0 && tail.length == 1 && tail[0].isEmpty())
			tail = new String[0];
		if (gap < 0 ? head.length != 8 : head.length + tail.length > 7)
			return null;
		byte[] ip = new byte[16];
		for (int i = 0; i < head.length + tail.length; i++)
		{
			int group = number(i < head.length ? head[i] : tail[i - head.length], 16, 4);
			if (group < 0)
				return null;
			int slot = i < head.length ? i : 8 - tail.length + i - head.length;
			ip[2 * slot] = (byte) (group >> 8);
			ip[2 * slot + 1] = (byte) group;
		}
		return ip;
	}

	static int number(String text, int radix, int digits)
	{
		if (text.isEmpty() || text.length() > digits)
			return -1;
		int value = 0;
		for (int i = 0; i < text.length(); i++)
		{
			int digit = Character.digit(text.charAt(i), radix);
			if (digit < 0)
				return -1;
			value = value * radix + digit;
		}
		return value;
	}
}
