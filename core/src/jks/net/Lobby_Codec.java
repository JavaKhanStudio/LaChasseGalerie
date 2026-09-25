package jks.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * The lobby protocol's bytes : {@link Lobby_Message} in, a packet out, and back. Pure, like
 * {@link Net_Codec} : no socket, no game, and a browser build compiles it.
 *
 * Every packet is
 * <pre>
 *   'L' u8 | version u8 | type u8 | body | CRC-32 u32
 * </pre>
 * big-endian. The 'L' is what tells a lobby packet from a game packet on the one socket both share :
 * a game packet starts with {@link Net_Codec#VERSION}, which will never climb to 76. The checksum is
 * over everything before it.
 *
 * Bodies :
 * <pre>
 *   OUTDATED   (empty)                                          frozen : the header's version is the service's
 *   HOST       game u8 | code c6 | players u8 | seats u8 | unlisted u8 | tabs u8 | candidates
 *   HOSTED     code c6 | you addr | relay
 *   CLOSE      code c6
 *   BROWSE     game u8
 *   LISTING    game u8 | total u16 | rows u8 | rows x (code c6 | players u8 | seats u8)
 *   JOIN       game u8 | code c6 | candidates
 *   JOINED     code c6 | you addr | host candidates | relay
 *   PEER       code c6 | joiner candidates
 *   REFUSED    code c6 | reason u8 | game u8
 *   PING       (empty)
 *   PONG       you addr
 *   OFFER      game u8 | code c6 | sdp                          (r79, version 4 : the WebSocket front only)
 *   ANSWER     code c6 | sdp                                    (the WebSocket front only)
 *   OFFER_PART code c6 | call u16 | part u8 | parts u8 | bytes  (service to host, over UDP)
 *   ANSWER_PART                     the same                    (host to service, over UDP)
 *
 *   code c6      six characters of {@link #CODE_ALPHABET}, or six zero bytes for none (HOST, REFUSED)
 *   addr         length u8 (1-64) | printable ASCII
 *   candidates   count u8 (0-5) | count x addr
 *   relay        0 u8 when the service has none, or 1 u8 | server addr | username addr | password addr   (r45, version 2)
 *   unlisted     0 u8 for a lobby BROWSE lists, 1 u8 for one joinable by its code only   (r76, version 3)
 *   tabs         0 u8 for a host that cannot take a browser tab, 1 u8 for one that can  (r85, version 5)
 *   sdp          length u16 (1-{@link #MAX_SDP}) | that many bytes of {@link #isSdp} text
 *   bytes        the rest of the body : 1-{@link #CHUNK_BYTES} bytes of that text, part of a description
 * </pre>
 * OUTDATED with no body is the one layout no version may change : a service that is newer than a
 * game still has a way to say so. {@link #decode} hands one of another version back instead of refusing it.
 *
 * A WebRTC description (r79) is about 1 kB, past one packet : OFFER and ANSWER carry it whole and are the
 * two messages allowed past {@link Net_Transport#MAX_PAYLOAD}, up to {@link #MAX_MESSAGE}, because they only
 * ever go over the WebSocket front (Transport_Ws), where a message is not a datagram. Over UDP a description
 * goes in parts of at most {@link #CHUNK_BYTES}, which {@link Lobby_Chunks} cuts and puts back together.
 *
 * Like the game codec, encoding past {@link Net_Transport#MAX_PAYLOAD} throws, and decoding refuses a
 * packet whole - never half-reads it - when it is truncated, corrupted, another version or not a value
 * an encoder writes.
 */
public final class Lobby_Codec
{
	/** Bump on ANY change to a layout above, except OUTDATED's, which never changes. */
	public static final int VERSION = 5;
	public static final int MAGIC = 'L';

	/** No 0/O, no 1/I : a code is read aloud and typed by a person. 32 letters, six of them : 2^30 codes. */
	public static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	public static final int CODE_LENGTH = 6;
	public static final int MAX_ADDRESS = 64;
	public static final int MAX_CANDIDATES = 5;
	/** What one LISTING row takes, and how many a LISTING carries at most : the newest, when more are open. */
	public static final int ROW_BYTES = CODE_LENGTH + 2, MAX_ROWS = 100;

	static final int HEADER = 3, CHECKSUM = 4;

	/** A description's bytes in one part, and parts in one description : 8 kB, several times what a browser makes. */
	public static final int CHUNK_BYTES = 1024, MAX_PARTS = 8, MAX_SDP = CHUNK_BYTES * MAX_PARTS;
	/** The biggest OFFER : what the WebSocket front must take in one message. */
	public static final int MAX_MESSAGE = HEADER + 1 + CODE_LENGTH + 2 + MAX_SDP + CHECKSUM;
	/** An OFFER_PART or ANSWER_PART before its bytes. */
	static final int PART_HEADER = CODE_LENGTH + 2 + 1 + 1;

	private Lobby_Codec()
	{
	}

	/** True when the packet starts like a lobby packet : what a shared socket routes on before decoding anything. */
	public static boolean isLobbyPacket(ByteBuffer packet)
	{
		return packet.remaining() > 0 && (packet.get(packet.position()) & 0xFF) == MAGIC;
	}

	/** The code as the service keeps it, from what a person typed : upper case, no spaces or dashes ; null if it cannot be one. */
	public static String normalise(String typed)
	{
		if (typed == null)
			return null;
		String code = typed.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
		return isCode(code) ? code : null;
	}

	public static boolean isCode(String code)
	{
		if (code == null || code.length() != CODE_LENGTH)
			return false;
		for (int i = 0; i < CODE_LENGTH; i++)
			if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0)
				return false;
		return true;
	}

	/** A new buffer holding the packet, ready to hand to {@link Net_Transport#send}. */
	public static ByteBuffer encode(Lobby_Message message)
	{
		ByteBuffer out = ByteBuffer.allocate(sizeOf(message));
		encode(message, out);
		out.flip();
		return out;
	}

	/** The bytes a message takes on the wire, header and checksum included. */
	public static int sizeOf(Lobby_Message message)
	{
		int body;
		switch (message.type())
		{
			case HOST:
				body = 1 + CODE_LENGTH + 4 + candidatesSize(((Lobby_Message.Host) message).candidates);
				break;
			case HOSTED:
				body = CODE_LENGTH + addressSize(((Lobby_Message.Hosted) message).you) + relaySize(((Lobby_Message.Hosted) message).relay);
				break;
			case CLOSE:
				body = CODE_LENGTH;
				break;
			case BROWSE:
				body = 1;
				break;
			case LISTING:
				body = 1 + 2 + 1 + ((Lobby_Message.Listing) message).rows.size() * ROW_BYTES;
				break;
			case JOIN:
				body = 1 + CODE_LENGTH + candidatesSize(((Lobby_Message.Join) message).candidates);
				break;
			case JOINED:
				Lobby_Message.Joined joined = (Lobby_Message.Joined) message;
				body = CODE_LENGTH + addressSize(joined.you) + candidatesSize(joined.host) + relaySize(joined.relay);
				break;
			case PEER:
				body = CODE_LENGTH + candidatesSize(((Lobby_Message.Peer) message).joiner);
				break;
			case REFUSED:
				body = CODE_LENGTH + 2;
				break;
			case PONG:
				body = addressSize(((Lobby_Message.Pong) message).you);
				break;
			case OFFER:
				body = 1 + CODE_LENGTH + sdpSize(((Lobby_Message.Offer) message).sdp);
				break;
			case ANSWER:
				body = CODE_LENGTH + sdpSize(((Lobby_Message.Answer) message).sdp);
				break;
			case OFFER_PART:
			case ANSWER_PART:
				byte[] bytes = ((Lobby_Message.Part) message).bytes;
				body = PART_HEADER + (bytes == null ? 0 : bytes.length);
				break;
			default:
				body = 0;
				break;
		}
		return HEADER + body + CHECKSUM;
	}

	/** The most bytes a message of this type may take : a packet, or for OFFER and ANSWER a WebSocket message. */
	public static int limit(Lobby_Message.Type type)
	{
		return type == Lobby_Message.Type.OFFER || type == Lobby_Message.Type.ANSWER ? MAX_MESSAGE : Net_Transport.MAX_PAYLOAD;
	}

	/**
	 * Writes the packet at the buffer's position and leaves the position after it.
	 *
	 * @throws IllegalArgumentException if it would pass {@link #limit}, or a field
	 *         cannot be written : a code that is not one, an address too long or not ASCII, too many rows
	 */
	public static void encode(Lobby_Message message, ByteBuffer out)
	{
		int size = sizeOf(message);
		if (size > limit(message.type()))
			throw new IllegalArgumentException("a " + message.type() + " is " + size + " B, past the " + limit(message.type()) + " B it may be");
		if (out.order() != ByteOrder.BIG_ENDIAN)
			throw new IllegalArgumentException("the protocol is big-endian, the buffer is " + out.order());
		if (out.remaining() < size)
			throw new IllegalArgumentException("a " + message.type() + " takes " + size + " B, the buffer has " + out.remaining());

		int start = out.position();
		out.put((byte) MAGIC);
		out.put((byte) (message.type() == Lobby_Message.Type.OUTDATED ? ((Lobby_Message.Outdated) message).version : VERSION));
		out.put((byte) message.type().ordinal());
		switch (message.type())
		{
			case HOST:
				Lobby_Message.Host host = (Lobby_Message.Host) message;
				out.put(Net_Codec.u8(host.game, "game version"));
				putCode(out, host.code, true);
				if (host.seats < 1 || host.players > host.seats)
					throw new IllegalArgumentException(host.players + " players of " + host.seats + " seats");
				out.put(Net_Codec.u8(host.players, "players"));
				out.put(Net_Codec.u8(host.seats, "seats"));
				out.put((byte) (host.unlisted ? 1 : 0));
				out.put((byte) (host.tabs ? 1 : 0));
				putCandidates(out, host.candidates);
				break;
			case HOSTED:
				putCode(out, ((Lobby_Message.Hosted) message).code, false);
				putAddress(out, ((Lobby_Message.Hosted) message).you);
				putRelay(out, ((Lobby_Message.Hosted) message).relay);
				break;
			case CLOSE:
				putCode(out, ((Lobby_Message.Close) message).code, false);
				break;
			case BROWSE:
				out.put(Net_Codec.u8(((Lobby_Message.Browse) message).game, "game version"));
				break;
			case LISTING:
				Lobby_Message.Listing listing = (Lobby_Message.Listing) message;
				if (listing.rows.size() > MAX_ROWS || listing.rows.size() > listing.total)
					throw new IllegalArgumentException(listing.rows.size() + " rows of " + listing.total + ", at most " + MAX_ROWS);
				out.put(Net_Codec.u8(listing.game, "game version"));
				out.putShort(Net_Codec.u16(listing.total, "total"));
				out.put((byte) listing.rows.size());
				for (Lobby_Message.Row row : listing.rows)
				{
					putCode(out, row.code, false);
					if (row.seats < 1 || row.players > row.seats)
						throw new IllegalArgumentException(row.players + " players of " + row.seats + " seats");
					out.put(Net_Codec.u8(row.players, "players"));
					out.put(Net_Codec.u8(row.seats, "seats"));
				}
				break;
			case JOIN:
				Lobby_Message.Join join = (Lobby_Message.Join) message;
				out.put(Net_Codec.u8(join.game, "game version"));
				putCode(out, join.code, false);
				putCandidates(out, join.candidates);
				break;
			case JOINED:
				Lobby_Message.Joined joined = (Lobby_Message.Joined) message;
				putCode(out, joined.code, false);
				putAddress(out, joined.you);
				putCandidates(out, joined.host);
				putRelay(out, joined.relay);
				break;
			case PEER:
				putCode(out, ((Lobby_Message.Peer) message).code, false);
				putCandidates(out, ((Lobby_Message.Peer) message).joiner);
				break;
			case REFUSED:
				Lobby_Message.Refused refused = (Lobby_Message.Refused) message;
				if (refused.reason == null)
					throw new IllegalArgumentException("a REFUSED needs a reason");
				putCode(out, refused.code, true);
				out.put((byte) refused.reason.ordinal());
				out.put(Net_Codec.u8(refused.game, "game version"));
				break;
			case PONG:
				putAddress(out, ((Lobby_Message.Pong) message).you);
				break;
			case OFFER:
				Lobby_Message.Offer offer = (Lobby_Message.Offer) message;
				out.put(Net_Codec.u8(offer.game, "game version"));
				putCode(out, offer.code, false);
				putSdp(out, offer.sdp);
				break;
			case ANSWER:
				putCode(out, ((Lobby_Message.Answer) message).code, false);
				putSdp(out, ((Lobby_Message.Answer) message).sdp);
				break;
			case OFFER_PART:
			case ANSWER_PART:
				Lobby_Message.Part part = (Lobby_Message.Part) message;
				if (part.parts < 1 || part.parts > MAX_PARTS || part.part < 0 || part.part >= part.parts)
					throw new IllegalArgumentException("part " + part.part + " of " + part.parts + ", at most " + MAX_PARTS);
				if (part.bytes == null || part.bytes.length < 1 || part.bytes.length > CHUNK_BYTES || !isSdp(part.bytes, 0, part.bytes.length))
					throw new IllegalArgumentException("a part is 1 to " + CHUNK_BYTES + " B of description text");
				putCode(out, part.code, false);
				out.putShort(Net_Codec.u16(part.call, "call"));
				out.put((byte) part.part);
				out.put((byte) part.parts);
				out.put(part.bytes);
				break;
			default:
				// OUTDATED, PING : the header says it all
				break;
		}
		out.putInt(Net_Codec.crc32(out, start, out.position()));
	}

	/**
	 * Reads one whole packet : the buffer's remaining bytes, all of them. Consumes nothing and keeps no
	 * reference. An OUTDATED of any version is returned, with its version : it is the answer to "why
	 * does the service not understand me".
	 */
	public static Lobby_Message decode(ByteBuffer packet) throws Net_Rejected
	{
		int start = packet.position(), end = packet.limit(), length = end - start;
		if (!isLobbyPacket(packet))
			throw new Net_Rejected(Net_Rejected.Reason.NOT_OURS, -1, length == 0 ? "empty" : "first byte " + (packet.get(start) & 0xFF));
		int version = length > 1 ? packet.get(start + 1) & 0xFF : -1;
		if (length < HEADER + CHECKSUM)
			throw new Net_Rejected(Net_Rejected.Reason.TOO_SHORT, version, length + " B");

		ByteBuffer in = packet.duplicate();
		in.order(ByteOrder.BIG_ENDIAN);
		int bodyEnd = end - CHECKSUM;
		if (in.getInt(bodyEnd) != Net_Codec.crc32(in, start, bodyEnd))
			throw new Net_Rejected(Net_Rejected.Reason.CHECKSUM, version, length + " B");

		int code = in.get(start + 2) & 0xFF;
		if (code == Lobby_Message.Type.OUTDATED.ordinal() && length == HEADER + CHECKSUM)
		{
			Lobby_Message.Outdated outdated = new Lobby_Message.Outdated();
			outdated.version = version;
			return outdated;
		}
		if (version != VERSION)
			throw new Net_Rejected(Net_Rejected.Reason.VERSION, version, "lobby version " + version + ", this game speaks " + VERSION);
		Lobby_Message.Type type = Lobby_Message.Type.of(code);
		if (type == null)
			throw new Net_Rejected(Net_Rejected.Reason.UNKNOWN_TYPE, version, "type " + code);

		in.position(start + HEADER);
		in.limit(bodyEnd);
		Lobby_Message message;
		try
		{
			message = read(type, in, version);
		}
		catch (java.nio.BufferUnderflowException e)
		{
			throw new Net_Rejected(Net_Rejected.Reason.LENGTH, version, "a " + type + " cut short");
		}
		if (in.hasRemaining())
			throw new Net_Rejected(Net_Rejected.Reason.LENGTH, version, "a " + type + " with " + in.remaining() + " B left over");
		return message;
	}

	static Lobby_Message read(Lobby_Message.Type type, ByteBuffer in, int version) throws Net_Rejected
	{
		switch (type)
		{
			case HOST:
				Lobby_Message.Host host = new Lobby_Message.Host();
				host.game = in.get() & 0xFF;
				host.code = getCode(in, version, true);
				host.players = in.get() & 0xFF;
				host.seats = in.get() & 0xFF;
				if (host.seats < 1 || host.players > host.seats)
					throw bad(version, host.players + " players of " + host.seats + " seats");
				int unlisted = in.get() & 0xFF;
				if (unlisted > 1)
					throw bad(version, "unlisted " + unlisted);
				host.unlisted = unlisted == 1;
				int tabs = in.get() & 0xFF;
				if (tabs > 1)
					throw bad(version, "tabs " + tabs);
				host.tabs = tabs == 1;
				getCandidates(in, version, host.candidates);
				return host;
			case HOSTED:
				Lobby_Message.Hosted hosted = new Lobby_Message.Hosted(getCode(in, version, false), getAddress(in, version));
				hosted.relay = getRelay(in, version);
				return hosted;
			case CLOSE:
				return new Lobby_Message.Close(getCode(in, version, false));
			case BROWSE:
				Lobby_Message.Browse browse = new Lobby_Message.Browse();
				browse.game = in.get() & 0xFF;
				return browse;
			case LISTING:
				Lobby_Message.Listing listing = new Lobby_Message.Listing();
				listing.game = in.get() & 0xFF;
				listing.total = in.getShort() & 0xFFFF;
				int rows = in.get() & 0xFF;
				if (rows > MAX_ROWS || rows > listing.total)
					throw bad(version, rows + " rows of " + listing.total);
				if (in.remaining() != rows * ROW_BYTES)
					throw new Net_Rejected(Net_Rejected.Reason.LENGTH, version, rows + " rows need " + rows * ROW_BYTES + " B, got " + in.remaining());
				for (int i = 0; i < rows; i++)
				{
					Lobby_Message.Row row = new Lobby_Message.Row(getCode(in, version, false), in.get() & 0xFF, in.get() & 0xFF);
					if (row.seats < 1 || row.players > row.seats)
						throw bad(version, row.players + " players of " + row.seats + " seats");
					listing.rows.add(row);
				}
				return listing;
			case JOIN:
				Lobby_Message.Join join = new Lobby_Message.Join();
				join.game = in.get() & 0xFF;
				join.code = getCode(in, version, false);
				getCandidates(in, version, join.candidates);
				return join;
			case JOINED:
				Lobby_Message.Joined joined = new Lobby_Message.Joined();
				joined.code = getCode(in, version, false);
				joined.you = getAddress(in, version);
				getCandidates(in, version, joined.host);
				joined.relay = getRelay(in, version);
				return joined;
			case PEER:
				Lobby_Message.Peer peer = new Lobby_Message.Peer();
				peer.code = getCode(in, version, false);
				getCandidates(in, version, peer.joiner);
				return peer;
			case REFUSED:
				String code = getCode(in, version, true);
				int reason = in.get() & 0xFF;
				if (Lobby_Message.Refused.Reason.of(reason) == null)
					throw bad(version, "refusal reason " + reason);
				return new Lobby_Message.Refused(code, Lobby_Message.Refused.Reason.of(reason), in.get() & 0xFF);
			case PING:
				return new Lobby_Message.Ping();
			case PONG:
				return new Lobby_Message.Pong(getAddress(in, version));
			case OFFER:
				Lobby_Message.Offer offer = new Lobby_Message.Offer();
				offer.game = in.get() & 0xFF;
				offer.code = getCode(in, version, false);
				offer.sdp = getSdp(in, version);
				return offer;
			case ANSWER:
				String answered = getCode(in, version, false);
				return new Lobby_Message.Answer(answered, getSdp(in, version));
			case OFFER_PART:
			case ANSWER_PART:
				Lobby_Message.Part part = new Lobby_Message.Part(type);
				part.code = getCode(in, version, false);
				part.call = in.getShort() & 0xFFFF;
				part.part = in.get() & 0xFF;
				part.parts = in.get() & 0xFF;
				if (part.parts < 1 || part.parts > MAX_PARTS || part.part >= part.parts)
					throw bad(version, "part " + part.part + " of " + part.parts);
				if (in.remaining() < 1 || in.remaining() > CHUNK_BYTES)
					throw new Net_Rejected(Net_Rejected.Reason.LENGTH, version, "a part of " + in.remaining() + " B");
				part.bytes = new byte[in.remaining()];
				in.get(part.bytes);
				if (!isSdp(part.bytes, 0, part.bytes.length))
					throw bad(version, "a part that is not description text");
				return part;
			default:
				// OUTDATED with a body : not the frozen layout
				throw new Net_Rejected(Net_Rejected.Reason.LENGTH, version, "an OUTDATED has no body");
		}
	}

	// ---------------------------------------------------------------- fields

	static void putCode(ByteBuffer out, String code, boolean mayBeNone)
	{
		if (code == null && mayBeNone)
		{
			for (int i = 0; i < CODE_LENGTH; i++)
				out.put((byte) 0);
			return;
		}
		if (!isCode(code))
			throw new IllegalArgumentException("not a lobby code : " + code);
		for (int i = 0; i < CODE_LENGTH; i++)
			out.put((byte) code.charAt(i));
	}

	static String getCode(ByteBuffer in, int version, boolean mayBeNone) throws Net_Rejected
	{
		byte[] bytes = new byte[CODE_LENGTH];
		in.get(bytes);
		boolean none = true;
		char[] chars = new char[CODE_LENGTH];
		for (int i = 0; i < CODE_LENGTH; i++)
		{
			none &= bytes[i] == 0;
			chars[i] = (char) (bytes[i] & 0xFF);
		}
		if (none && mayBeNone)
			return null;
		String code = new String(chars);
		if (!isCode(code))
			throw bad(version, "not a lobby code");
		return code;
	}

	static int addressSize(String address)
	{
		return 1 + (address == null ? 0 : address.length());
	}

	static int candidatesSize(List<String> candidates)
	{
		int size = 1;
		for (String address : candidates)
			size += addressSize(address);
		return size;
	}

	static void putAddress(ByteBuffer out, String address)
	{
		if (!isAddress(address))
			throw new IllegalArgumentException("not an address the lobby can carry : " + address);
		out.put((byte) address.length());
		for (int i = 0; i < address.length(); i++)
			out.put((byte) address.charAt(i));
	}

	static String getAddress(ByteBuffer in, int version) throws Net_Rejected
	{
		int length = in.get() & 0xFF;
		if (length < 1 || length > MAX_ADDRESS)
			throw bad(version, "an address of " + length + " B");
		char[] chars = new char[length];
		for (int i = 0; i < length; i++)
			chars[i] = (char) (in.get() & 0xFF);
		String address = new String(chars);
		if (!isAddress(address))
			throw bad(version, "an address that is not printable ASCII");
		return address;
	}

	/** Printable ASCII with no space, 1 to 64 characters : "203.0.113.7:7777", "[2001:db8::1]:7777". */
	public static boolean isAddress(String address)
	{
		if (address == null || address.isEmpty() || address.length() > MAX_ADDRESS)
			return false;
		for (int i = 0; i < address.length(); i++)
			if (address.charAt(i) < 0x21 || address.charAt(i) > 0x7E)
				return false;
		return true;
	}

	static void putCandidates(ByteBuffer out, List<String> candidates)
	{
		if (candidates.size() > MAX_CANDIDATES)
			throw new IllegalArgumentException(candidates.size() + " candidates, at most " + MAX_CANDIDATES);
		out.put((byte) candidates.size());
		for (String address : candidates)
			putAddress(out, address);
	}

	static void getCandidates(ByteBuffer in, int version, List<String> into) throws Net_Rejected
	{
		int count = in.get() & 0xFF;
		if (count > MAX_CANDIDATES)
			throw bad(version, count + " candidates");
		for (int i = 0; i < count; i++)
			into.add(getAddress(in, version));
	}

	static int relaySize(Lobby_Message.Relay relay)
	{
		return 1 + (relay == null ? 0 : addressSize(relay.server) + addressSize(relay.username) + addressSize(relay.password));
	}

	static void putRelay(ByteBuffer out, Lobby_Message.Relay relay)
	{
		out.put((byte) (relay == null ? 0 : 1));
		if (relay == null)
			return;
		putAddress(out, relay.server);
		putAddress(out, relay.username);
		putAddress(out, relay.password);
	}

	static Lobby_Message.Relay getRelay(ByteBuffer in, int version) throws Net_Rejected
	{
		int present = in.get() & 0xFF;
		if (present > 1)
			throw bad(version, "a relay flag of " + present);
		return present == 0 ? null : new Lobby_Message.Relay(getAddress(in, version), getAddress(in, version), getAddress(in, version));
	}

	/**
	 * What a description may hold : printable ASCII, tab, CR and LF, 1 to {@link #MAX_SDP} of them. SDP may
	 * carry UTF-8 in a few free-text fields ; no browser writes any there, and ASCII needs no charset in GWT.
	 */
	public static boolean isSdp(String sdp)
	{
		if (sdp == null || sdp.isEmpty() || sdp.length() > MAX_SDP)
			return false;
		for (int i = 0; i < sdp.length(); i++)
			if (!isSdpChar(sdp.charAt(i)))
				return false;
		return true;
	}

	static boolean isSdp(byte[] bytes, int from, int to)
	{
		for (int i = from; i < to; i++)
			if (!isSdpChar((char) (bytes[i] & 0xFF)))
				return false;
		return true;
	}

	static boolean isSdpChar(char c)
	{
		return c >= 0x20 && c <= 0x7E || c == '\t' || c == '\r' || c == '\n';
	}

	static int sdpSize(String sdp)
	{
		return 2 + (sdp == null ? 0 : sdp.length());
	}

	static void putSdp(ByteBuffer out, String sdp)
	{
		if (!isSdp(sdp))
			throw new IllegalArgumentException("not a description the lobby can carry : " + (sdp == null ? null : sdp.length() + " chars"));
		out.putShort((short) sdp.length());
		for (int i = 0; i < sdp.length(); i++)
			out.put((byte) sdp.charAt(i));
	}

	static String getSdp(ByteBuffer in, int version) throws Net_Rejected
	{
		int length = in.getShort() & 0xFFFF;
		if (length < 1 || length > MAX_SDP)
			throw bad(version, "a description of " + length + " B");
		byte[] bytes = new byte[length];
		in.get(bytes);
		if (!isSdp(bytes, 0, length))
			throw bad(version, "a description that is not text");
		return ascii(bytes, 0, length);
	}

	/** Bytes already checked to be ASCII, as a String, without asking for a charset. */
	static String ascii(byte[] bytes, int from, int to)
	{
		char[] chars = new char[to - from];
		for (int i = from; i < to; i++)
			chars[i - from] = (char) (bytes[i] & 0xFF);
		return new String(chars);
	}

	static Net_Rejected bad(int version, String detail)
	{
		return new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, version, detail);
	}
}
