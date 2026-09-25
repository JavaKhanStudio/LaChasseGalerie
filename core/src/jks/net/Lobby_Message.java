package jks.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One packet between a player and the lobby service, decoded : what {@link Lobby_Codec} turns into
 * bytes and back. Phase 2.1 of docs/online-multiplayer.md.
 *
 * The service holds lobbies and sees endpoints, nothing else : no game logic, no snapshot, no input.
 * It goes over the SAME socket the game uses, so every address it sends back is the mapping a peer's
 * game traffic will really arrive on. Like the game protocol there is no reliability layer : every
 * request is safe to send again until it is answered, and a host's HOST is repeated for as long as
 * the lobby should live.
 *
 * An address is the transport's text ({@link Net_Peer#address()}) : read it, print it, hand it back
 * to the same kind of transport, never parse it. A candidate list holds the addresses a peer may be
 * reached at, the one the service saw first.
 *
 * Plain data, no sockets : a browser build compiles it.
 */
public abstract class Lobby_Message
{
	/** The byte on the wire. Never renumber one : bump {@link Lobby_Codec#VERSION} instead. OUTDATED is 0 forever. */
	public enum Type
	{
		OUTDATED, HOST, HOSTED, CLOSE, BROWSE, LISTING, JOIN, JOINED, PEER, REFUSED, PING, PONG,
		/** A tab's WebRTC signalling (r79, version 4) : whole over the WebSocket front, in parts over UDP. */
		OFFER, ANSWER, OFFER_PART, ANSWER_PART;

		static Type of(int code)
		{
			Type[] all = values();
			return code >= 0 && code < all.length ? all[code] : null;
		}
	}

	public abstract Type type();

	/**
	 * Service to anyone who speaks another lobby version : "I speak the version in this header". Its
	 * layout - the header with type 0 and no body - is the one thing no version may change, so an old
	 * game can still tell a person it is out of date.
	 */
	public static final class Outdated extends Lobby_Message
	{
		/** The lobby protocol version the service speaks. Filled by the decoder, from the header. */
		public int version = Lobby_Codec.VERSION;

		@Override
		public Type type()
		{
			return Type.OUTDATED;
		}
	}

	/**
	 * Host to service, every {@code REFRESH} : this lobby is alive, with this many players of this many
	 * seats. The first one has no code ; a later one carries the code it was given, so a lobby the
	 * service forgot (a lost refresh, a restart) comes back under the code its friends were told.
	 */
	public static final class Host extends Lobby_Message
	{
		/** The game protocol the host speaks, {@link Net_Codec#VERSION} : only its equals may join. */
		public int game = Net_Codec.VERSION;
		/** The code this host was given, or null for a new lobby. */
		public String code;
		public int players, seats;
		/** True for a private lobby (r76) : BROWSE never lists it, and its code alone lets a friend JOIN. */
		public boolean unlisted;
		/** True when the host answers a browser tab's offer (r85) : it has WebRTC. The service refuses a tab NO_TABS otherwise. */
		public boolean tabs;
		/** Addresses the host can also be reached at, besides the one the service sees : its LAN, its IPv6. */
		public final List<String> candidates = new ArrayList<String>();

		@Override
		public Type type()
		{
			return Type.HOST;
		}
	}

	/**
	 * The game's relay (r45), when the service has one : where it is, and a credential for it that runs
	 * out by itself (TURN REST : the username is its expiry, the password the service's signature of it).
	 * The relay's secret stays with the service ; a credential that leaks is good for a day.
	 */
	public static final class Relay
	{
		/** The relay's ip:port. */
		public String server;
		public String username, password;

		public Relay()
		{
		}

		public Relay(String server, String username, String password)
		{
			this.server = server;
			this.username = username;
			this.password = password;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Relay && ((Relay) other).server.equals(server) && ((Relay) other).username.equals(username)
					&& ((Relay) other).password.equals(password);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(server, username, password);
		}
	}

	/** Service to host : your lobby is open under this code, and this is where your packets come from. */
	public static final class Hosted extends Lobby_Message
	{
		public String code;
		/** The host's own public address, as the service saw it : free STUN. */
		public String you;
		/** The relay a joiner may come through, or null : the host ranks an address on it as RELAYED. */
		public Relay relay;

		public Hosted()
		{
		}

		public Hosted(String code, String you)
		{
			this.code = code;
			this.you = you;
		}

		@Override
		public Type type()
		{
			return Type.HOSTED;
		}
	}

	/** Host to service : the lobby is over, forget it now rather than when its refreshes stop. */
	public static final class Close extends Lobby_Message
	{
		public String code;

		public Close()
		{
		}

		public Close(String code)
		{
			this.code = code;
		}

		@Override
		public Type type()
		{
			return Type.CLOSE;
		}
	}

	/** Player to service : which lobbies could I join ? Only those of the same game version are listed. */
	public static final class Browse extends Lobby_Message
	{
		public int game = Net_Codec.VERSION;

		@Override
		public Type type()
		{
			return Type.BROWSE;
		}
	}

	/** One open lobby, as a list shows it. */
	public static final class Row
	{
		public String code;
		public int players, seats;

		public Row()
		{
		}

		public Row(String code, int players, int seats)
		{
			this.code = code;
			this.players = players;
			this.seats = seats;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Row && ((Row) other).code.equals(code) && ((Row) other).players == players && ((Row) other).seats == seats;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(code, players, seats);
		}

		@Override
		public String toString()
		{
			return code + " " + players + "/" + seats;
		}
	}

	/** Service to player : the open lobbies of that version, as many as fit one packet, newest first. */
	public static final class Listing extends Lobby_Message
	{
		public int game = Net_Codec.VERSION;
		/** How many lobbies of that version are open, which may be more than the rows sent. */
		public int total;
		public final List<Row> rows = new ArrayList<Row>();

		@Override
		public Type type()
		{
			return Type.LISTING;
		}
	}

	/** Player to service : put me in touch with the host of this code. Repeated until the game connection answers. */
	public static final class Join extends Lobby_Message
	{
		public int game = Net_Codec.VERSION;
		public String code;
		public final List<String> candidates = new ArrayList<String>();

		@Override
		public Type type()
		{
			return Type.JOIN;
		}
	}

	/** Service to joiner : the host of that code can be reached at these addresses, the public one first. */
	public static final class Joined extends Lobby_Message
	{
		public String code;
		/** The joiner's own public address, as the service saw it. */
		public String you;
		public final List<String> host = new ArrayList<String>();
		/** The relay to allocate on when no check gets through, or null when the service has none. */
		public Relay relay;

		@Override
		public Type type()
		{
			return Type.JOINED;
		}
	}

	/**
	 * Service to host, mirrored from a JOIN : someone is coming, from these addresses, the public one
	 * first. The host sends toward them so both ends open the mapping the other needs (the punch, r42).
	 * Every copy of a JOIN makes one of these : the host is told again rather than trusted to have heard.
	 */
	public static final class Peer extends Lobby_Message
	{
		public String code;
		public final List<String> joiner = new ArrayList<String>();

		@Override
		public Type type()
		{
			return Type.PEER;
		}
	}

	/** Service to a host or a joiner : no. */
	public static final class Refused extends Lobby_Message
	{
		public enum Reason
		{
			/** No open lobby has that code : mistyped, or the host is gone. */
			NO_SUCH_LOBBY,
			/** The lobby's game speaks another protocol : {@link Refused#game} says which. */
			VERSION,
			/** Every seat in that lobby is taken. */
			FULL,
			/** The service holds as many lobbies as it will : a HOST is refused, try later. */
			SERVICE_FULL,
			/** To a tab (r85, version 5) : that host has no WebRTC, so it cannot take a browser player. */
			NO_TABS;

			static Reason of(int code)
			{
				Reason[] all = values();
				return code >= 0 && code < all.length ? all[code] : null;
			}
		}

		public String code;
		public Reason reason;
		/** For VERSION : the game protocol the lobby speaks. Otherwise the asker's own. */
		public int game;

		public Refused()
		{
		}

		public Refused(String code, Reason reason, int game)
		{
			this.code = code;
			this.reason = reason;
			this.game = game;
		}

		@Override
		public Type type()
		{
			return Type.REFUSED;
		}
	}

	/** Player to service, when it has nothing else to say : keeps the NAT mapping to the service open. */
	public static final class Ping extends Lobby_Message
	{
		@Override
		public Type type()
		{
			return Type.PING;
		}
	}

	/**
	 * Tab to service, over the WebSocket front only (r79) : my WebRTC offer, for the host of this code. A
	 * description is whole, never trickled ({@code jks.rtc.Transport_Rtc} sends one once ICE gathering is
	 * complete), and at about 1 kB it is past one lobby packet : the service hands it to the host in
	 * {@link Part}s and brings the host's {@link Answer} back. Refused like a JOIN : no such lobby, another
	 * version, full, or a host with no WebRTC (NO_TABS).
	 */
	public static final class Offer extends Lobby_Message
	{
		public int game = Net_Codec.VERSION;
		public String code;
		/** The session description : SDP text, {@link Lobby_Codec#isSdp} (printable ASCII, tab, CR, LF). */
		public String sdp;

		public Offer()
		{
		}

		public Offer(String code, String sdp)
		{
			this.code = code;
			this.sdp = sdp;
		}

		@Override
		public Type type()
		{
			return Type.OFFER;
		}
	}

	/** Service to tab, over the WebSocket front : the host of this code answered your offer with this description. */
	public static final class Answer extends Lobby_Message
	{
		public String code;
		public String sdp;

		public Answer()
		{
		}

		public Answer(String code, String sdp)
		{
			this.code = code;
			this.sdp = sdp;
		}

		@Override
		public Type type()
		{
			return Type.ANSWER;
		}
	}

	/**
	 * One packet of a description, over UDP (r79) : an OFFER_PART is service to host, an ANSWER_PART host to
	 * service. The service names each offer with a {@link #call} ; the host answers under the same one. Parts
	 * come in any order and any may be lost : {@link Lobby_Chunks} gives the description back whole, or never.
	 */
	public static final class Part extends Lobby_Message
	{
		/** OFFER_PART or ANSWER_PART. */
		public final Type kind;
		public String code;
		/** The service's name for this offer and its answer, u16. */
		public int call;
		/** This part's place, from 0, of {@link #parts}. */
		public int part, parts;
		public byte[] bytes;

		public Part(Type kind)
		{
			if (kind != Type.OFFER_PART && kind != Type.ANSWER_PART)
				throw new IllegalArgumentException("a part of an offer or of an answer, not of a " + kind);
			this.kind = kind;
		}

		@Override
		public Type type()
		{
			return kind;
		}
	}

	/** Service to player : where your packets come from. The same answer a STUN server gives. */
	public static final class Pong extends Lobby_Message
	{
		public String you;

		public Pong()
		{
		}

		public Pong(String you)
		{
			this.you = you;
		}

		@Override
		public Type type()
		{
			return Type.PONG;
		}
	}
}
