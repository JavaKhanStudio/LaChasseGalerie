package jks.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The part of TURN (RFC 8656) the game's relay needs (r45) : allocate a relayed address, keep it,
 * let one peer's ip through it, and move packets through it with Send and Data indications. Beside
 * {@link Stun_Codec}, on the same framing : a TURN packet is a STUN packet, and a shared socket routes
 * it by where it came from (the relay server), not by its first byte.
 *
 * NOT ChannelData, on purpose : a ChannelData packet starts with its channel number, 0x40-0x4F, and 'L'
 * (0x4C) is how the one socket tells a lobby packet. Indications start with 0x00 like every STUN packet.
 * They cost 36 B a packet on IPv4 instead of 4, which {@link #MAX_RELAYED} accounts for.
 *
 * A packet is
 * <pre>
 *   type u16 | length u16 | magic cookie u32 | transaction u96 | attributes
 *
 *   Allocate request          REQUESTED-TRANSPORT (UDP) | LIFETIME? | auth
 *   Allocate success          XOR-RELAYED-ADDRESS | XOR-MAPPED-ADDRESS | LIFETIME | MESSAGE-INTEGRITY
 *   Refresh request           LIFETIME (0 frees it) | auth
 *   CreatePermission request  XOR-PEER-ADDRESS | auth
 *   any error                 ERROR-CODE | REALM? | NONCE?
 *   Send indication           XOR-PEER-ADDRESS | DATA           (client to server, no auth : the 5-tuple is the allocation's)
 *   Data indication           XOR-PEER-ADDRESS | DATA           (server to client)
 *
 *   auth = USERNAME | REALM | NONCE | MESSAGE-INTEGRITY, then FINGERPRINT on every request
 * </pre>
 * The long-term credential (RFC 8489 section 9.2) : key = MD5(username ":" realm ":" password), and
 * MESSAGE-INTEGRITY is HMAC-SHA1 with it over the packet before it, the length counting it. The first
 * request goes without auth and the 401 names the realm and nonce. The username and password are the
 * relay's REST pair (coturn use-auth-secret), minted by the lobby service : nothing secret is in the game.
 *
 * Uses javax.crypto, so desktop only : a tab relays through WebRTC's own TURN (d7) and never reaches this.
 */
public final class Turn_Codec
{
	public static final int ALLOCATE = 0x003, REFRESH = 0x004, SEND = 0x006, DATA = 0x007, CREATE_PERMISSION = 0x008;
	/** The class bits of a STUN type. */
	public static final int REQUEST = 0x000, INDICATION = 0x010, SUCCESS = 0x100, ERROR = 0x110;

	static final int USERNAME = 0x0006, MESSAGE_INTEGRITY = 0x0008, ERROR_CODE = 0x0009, LIFETIME = 0x000D, XOR_PEER_ADDRESS = 0x0012,
			DATA_ATTRIBUTE = 0x0013, REALM = 0x0014, NONCE = 0x0015, XOR_RELAYED_ADDRESS = 0x0016, REQUESTED_TRANSPORT = 0x0019;
	static final int UDP = 17;
	static final int INTEGRITY = 20;
	static final int MAX_TEXT = 763;

	/** The framing a Send or Data indication adds to a payload, at worst : header, an IPv6 peer, DATA's header and padding. */
	public static final int OVERHEAD = Stun_Codec.HEADER + 4 + 20 + 4 + 3;
	/** The biggest payload a relay moves in one packet the transport accepts. The game's measured peak snapshot is well under half. */
	public static final int MAX_RELAYED = Net_Transport.MAX_PAYLOAD - OVERHEAD;

	private Turn_Codec()
	{
	}

	/** One TURN packet, decoded or to encode. Fields a type does not carry stay null or -1. */
	public static final class Message
	{
		public int method, kind;
		public final byte[] transaction = new byte[Stun_Codec.TRANSACTION];
		/** ERROR-CODE : 401 unauthorised, 438 stale nonce, 437 allocation mismatch, 486 quota, 403 forbidden... -1 if none. */
		public int error = -1;
		public String reason, username, realm, nonce;
		/** XOR-RELAYED-ADDRESS, XOR-MAPPED-ADDRESS, XOR-PEER-ADDRESS, in the transport's text. */
		public String relayed, mapped, peer;
		/** Seconds ; -1 if absent. */
		public int lifetime = -1;
		/** DATA's bytes : a copy on decode. */
		public byte[] data;
		/** On decode : where MESSAGE-INTEGRITY starts, or -1 when there is none. {@link #authentic} checks it. */
		int integrityAt = -1;

		public Message()
		{
		}

		public Message(int method, int kind, byte[] transaction)
		{
			this.method = method;
			this.kind = kind;
			System.arraycopy(transaction, 0, this.transaction, 0, Stun_Codec.TRANSACTION);
		}

		public boolean is(int method, int kind)
		{
			return this.method == method && this.kind == kind;
		}

		@Override
		public String toString()
		{
			return "TURN 0x" + Integer.toHexString(method | kind) + (error >= 0 ? " error " + error + " " + reason : "");
		}
	}

	/** A STUN-framed packet of a TURN method : what the relay layer takes off the socket, a Binding is ICE's. */
	public static boolean isTurnPacket(ByteBuffer packet)
	{
		if (!Stun_Codec.isStunPacket(packet))
			return false;
		int method = methodOf(packet.getShort(packet.position()) & 0xFFFF);
		return method == ALLOCATE || method == REFRESH || method == SEND || method == DATA || method == CREATE_PERMISSION;
	}

	static int methodOf(int type)
	{
		return type & 0x000F | (type & 0x00E0) >> 1 | (type & 0x3E00) >> 2;
	}

	static int typeOf(int method, int kind)
	{
		return method & 0x000F | (method & 0x0070) << 1 | (method & 0x0F80) << 2 | kind;
	}

	/** The long-term credential's key : MD5 of "username:realm:password". */
	public static byte[] key(String username, String realm, String password)
	{
		try
		{
			return MessageDigest.getInstance("MD5").digest((username + ":" + realm + ":" + password).getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("every JDK has MD5", e);
		}
	}

	/**
	 * The packet, with MESSAGE-INTEGRITY when a key is given, and a
	 * FINGERPRINT on everything but an indication.
	 *
	 * @throws IllegalArgumentException if it would pass {@link Net_Transport#MAX_PAYLOAD}, or an address is not an ip:port
	 */
	public static ByteBuffer encode(Message message, byte[] key)
	{
		int dataSize = message.data == null ? 0 : message.data.length;
		ByteBuffer out = ByteBuffer.allocate(Net_Transport.MAX_PAYLOAD + 256 + dataSize).order(ByteOrder.BIG_ENDIAN);
		out.putShort((short) typeOf(message.method, message.kind));
		out.putShort((short) 0);
		out.putInt(Stun_Codec.MAGIC_COOKIE);
		out.put(message.transaction);

		if (message.method == ALLOCATE && message.kind == REQUEST)
		{
			Stun_Codec.attribute(out, REQUESTED_TRANSPORT, 4);
			out.put((byte) UDP).put((byte) 0).putShort((short) 0);
		}
		if (message.peer != null)
			address(out, XOR_PEER_ADDRESS, message.peer, message.transaction);
		if (message.relayed != null)
			address(out, XOR_RELAYED_ADDRESS, message.relayed, message.transaction);
		if (message.mapped != null)
			address(out, Stun_Codec.XOR_MAPPED_ADDRESS, message.mapped, message.transaction);
		if (message.lifetime >= 0)
		{
			Stun_Codec.attribute(out, LIFETIME, 4);
			out.putInt(message.lifetime);
		}
		if (message.error >= 0)
		{
			byte[] reason = (message.reason == null ? "" : message.reason).getBytes(StandardCharsets.UTF_8);
			Stun_Codec.attribute(out, ERROR_CODE, 4 + reason.length);
			out.putShort((short) 0).put((byte) (message.error / 100)).put((byte) (message.error % 100)).put(reason);
			Stun_Codec.pad(out);
		}
		if (message.data != null)
		{
			Stun_Codec.attribute(out, DATA_ATTRIBUTE, message.data.length);
			out.put(message.data);
			Stun_Codec.pad(out);
		}
		text(out, USERNAME, message.username);
		text(out, REALM, message.realm);
		text(out, NONCE, message.nonce);

		if (key != null)
		{
			// The length counts MESSAGE-INTEGRITY itself, and the HMAC covers everything before it
			out.putShort(2, (short) (out.position() - Stun_Codec.HEADER + 4 + INTEGRITY));
			byte[] mac = hmac(key, out.array(), 0, out.position());
			Stun_Codec.attribute(out, MESSAGE_INTEGRITY, INTEGRITY);
			out.put(mac);
		}
		if (message.kind != INDICATION)
		{
			out.putShort(2, (short) (out.position() - Stun_Codec.HEADER + 8));
			int crc = Net_Codec.crc32(out, 0, out.position());
			Stun_Codec.attribute(out, Stun_Codec.FINGERPRINT, 4);
			out.putInt(crc ^ Stun_Codec.FINGERPRINT_XOR);
		}
		out.putShort(2, (short) (out.position() - Stun_Codec.HEADER));
		if (out.position() > Net_Transport.MAX_PAYLOAD)
			throw new IllegalArgumentException("a " + message + " is " + out.position() + " B, past the " + Net_Transport.MAX_PAYLOAD + " B a packet may be");
		out.flip();
		return out;
	}

	/** Reads one whole STUN-framed packet. Refuses it whole : cut short, a length that is not the packet's, a FINGERPRINT that does not match. */
	public static Message decode(ByteBuffer packet) throws Net_Rejected
	{
		// Any STUN framing : a Binding decodes too (its MESSAGE-INTEGRITY is how RFC 5769's vector checks ours)
		if (!Stun_Codec.isStunPacket(packet))
			throw new Net_Rejected(Net_Rejected.Reason.NOT_OURS, -1, "not a STUN-framed packet");
		ByteBuffer in = packet.slice().order(ByteOrder.BIG_ENDIAN);
		int length = in.remaining();
		int declared = in.getShort(2) & 0xFFFF;
		if (declared != length - Stun_Codec.HEADER || declared % 4 != 0)
			throw new Net_Rejected(Net_Rejected.Reason.LENGTH, -1, "says " + declared + " B of attributes, carries " + (length - Stun_Codec.HEADER));

		Message message = new Message();
		int type = in.getShort(0) & 0xFFFF;
		message.method = methodOf(type);
		message.kind = type & 0x0110;
		in.position(8);
		in.get(message.transaction);

		boolean integrity = false;
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
			// After MESSAGE-INTEGRITY only a FINGERPRINT counts : anything else was not signed
			if (integrity && attribute != Stun_Codec.FINGERPRINT)
			{
				in.position(value + padded);
				continue;
			}
			switch (attribute)
			{
				case XOR_PEER_ADDRESS:
					message.peer = Stun_Codec.address(in, value, size, message.transaction, true);
					break;
				case XOR_RELAYED_ADDRESS:
					message.relayed = Stun_Codec.address(in, value, size, message.transaction, true);
					break;
				case Stun_Codec.XOR_MAPPED_ADDRESS:
					message.mapped = Stun_Codec.address(in, value, size, message.transaction, true);
					break;
				case LIFETIME:
					if (size != 4)
						throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "a LIFETIME of " + size + " B");
					message.lifetime = in.getInt(value);
					break;
				case ERROR_CODE:
					if (size < 4)
						throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "an ERROR-CODE of " + size + " B");
					message.error = (in.get(value + 2) & 0x07) * 100 + (in.get(value + 3) & 0xFF);
					message.reason = string(in, value + 4, size - 4);
					break;
				case DATA_ATTRIBUTE:
					message.data = new byte[size];
					for (int i = 0; i < size; i++)
						message.data[i] = in.get(value + i);
					break;
				case USERNAME:
					message.username = string(in, value, size);
					break;
				case REALM:
					message.realm = string(in, value, size);
					break;
				case NONCE:
					message.nonce = string(in, value, size);
					break;
				case MESSAGE_INTEGRITY:
					if (size != INTEGRITY)
						throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "a MESSAGE-INTEGRITY of " + size + " B");
					message.integrityAt = at;
					integrity = true;
					break;
				case Stun_Codec.FINGERPRINT:
					if (size != 4 || value + 4 != length)
						throw new Net_Rejected(Net_Rejected.Reason.LENGTH, -1, "a FINGERPRINT that is not the last 4 B");
					if ((in.getInt(value) ^ Stun_Codec.FINGERPRINT_XOR) != Net_Codec.crc32(in, 0, at))
						throw new Net_Rejected(Net_Rejected.Reason.CHECKSUM, -1, length + " B");
					break;
				default:
					break; // SOFTWARE, PASSWORD-ALGORITHMS, RESPONSE-ORIGIN : not ours to read
			}
			in.position(value + padded);
		}
		if ((message.kind == INDICATION) && (message.peer == null || message.data == null))
			throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "an indication without its peer or its data");
		return message;
	}

	/** True when the packet's MESSAGE-INTEGRITY is this key's : a success or error really from the relay we authenticated to. */
	public static boolean authentic(ByteBuffer packet, Message decoded, byte[] key)
	{
		if (decoded.integrityAt < 0 || key == null)
			return false;
		byte[] bytes = new byte[decoded.integrityAt];
		ByteBuffer in = packet.slice();
		in.get(bytes);
		int length = decoded.integrityAt - Stun_Codec.HEADER + 4 + INTEGRITY;
		bytes[2] = (byte) (length >> 8);
		bytes[3] = (byte) length;
		byte[] sent = new byte[INTEGRITY];
		for (int i = 0; i < INTEGRITY; i++)
			sent[i] = in.get(decoded.integrityAt + 4 + i);
		return MessageDigest.isEqual(sent, hmac(key, bytes, 0, bytes.length));
	}

	static byte[] hmac(byte[] key, byte[] bytes, int from, int to)
	{
		try
		{
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			mac.update(bytes, from, to - from);
			return mac.doFinal();
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("every JDK has HmacSHA1", e);
		}
	}

	static void address(ByteBuffer out, int type, String address, byte[] transaction)
	{
		byte[] ip = Stun_Codec.ipOf(address);
		if (ip == null)
			throw new IllegalArgumentException("not an ip:port TURN carries : " + address);
		ip = Arrays.copyOf(ip, ip.length);
		Stun_Codec.attribute(out, type, 4 + ip.length);
		out.put((byte) 0);
		out.put((byte) (ip.length == 4 ? 1 : 2));
		out.putShort((short) (Stun_Codec.portOf(address) ^ (Stun_Codec.MAGIC_COOKIE >>> 16)));
		Stun_Codec.xorIp(ip, transaction);
		out.put(ip);
	}

	static void text(ByteBuffer out, int type, String text)
	{
		if (text == null)
			return;
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_TEXT)
			throw new IllegalArgumentException("a TURN text of " + bytes.length + " B");
		Stun_Codec.attribute(out, type, bytes.length);
		out.put(bytes);
		Stun_Codec.pad(out);
	}

	static String string(ByteBuffer in, int at, int size) throws Net_Rejected
	{
		if (size > MAX_TEXT)
			throw new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, -1, "a TURN text of " + size + " B");
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++)
			bytes[i] = in.get(at + i);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
