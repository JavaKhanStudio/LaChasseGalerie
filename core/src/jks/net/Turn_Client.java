package jks.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * One relayed address on the game's ONE socket (r45, d6 -> relay) : the joiner's way to a host no
 * check gets through to. It allocates on the relay, lets the host's ip through (a TURN permission),
 * and from then on a packet to {@code "relay/<host ip:port>"} leaves as a Send indication to the relay,
 * which sends it on from the relayed address ; what the host sends to that address comes back as a Data
 * indication and is handed on as if from {@code "relay/<host ip:port>"}.
 *
 * Only the joiner allocates. The host needs nothing : to it the relayed address is one more public
 * address to check and answer, and it sends there from its own socket, whatever its router does, because
 * a permission is for an ip, not a port.
 *
 * Every request is sent again at {@link #RETRY_MS}, doubling, until {@link #GIVE_UP_MS} : then the relay
 * is {@link State#FAILED} and says why. The allocation is refreshed at half its lifetime and each
 * permission every {@link #PERMISSION_REFRESH_MS} (a permission lives five minutes). {@link #close}
 * frees it at once rather than leaving it to its lifetime.
 *
 * A relayed peer times out like a UDP one, after {@link Net_PeerTable#DEFAULT_TIMEOUT_MS} of silence :
 * the session above hears its host go quiet whichever way the packets went.
 *
 * Not thread safe. The owner routes the relay server's packets to {@link #received} and calls
 * {@link #update} every pump.
 */
public final class Turn_Client
{
	/** How a relayed peer's text starts : never an ip, so nothing mistakes it for one. */
	public static final String PREFIX = "relay/";
	public static final long RETRY_MS = 250, GIVE_UP_MS = 5_000;
	/** Asked for ; the relay may give less, and the refresh follows what it gave. */
	public static final int LIFETIME_S = 600;
	/** A permission lasts 300 s on the relay : refreshed well inside that. */
	public static final long PERMISSION_REFRESH_MS = 120_000;

	public enum State
	{
		IDLE, ALLOCATING, ALLOCATED, FAILED, CLOSED
	}

	private final Net_Transport shared;
	private final Net_Peer server;
	private final String username, password;
	private final LongSupplier clock;
	private final Random random;
	private final Net_PeerTable relayed;

	private State state = State.IDLE;
	private String realm, nonce, why;
	private byte[] key;
	private String address, mapped;
	private long allocatedAt, lifetimeMs, startedAt;

	/** Peer ip (no port) -> when its permission was last granted, or MIN_VALUE while asked. */
	private final Map<String, Long> permissions = new LinkedHashMap<String, Long>();
	private final Map<String, Request> pending = new LinkedHashMap<String, Request>();

	public int sent, arrived, tooBig, rejected;

	public Turn_Client(Net_Transport shared, String server, String username, String password, LongSupplier clock, Random random)
	{
		this.shared = shared;
		this.server = shared.resolve(server);
		this.username = username;
		this.password = password;
		this.clock = clock;
		this.random = random;
		this.relayed = new Net_PeerTable(clock, Net_PeerTable.DEFAULT_TIMEOUT_MS);
	}

	/** Asks for an allocation. Once ; a FAILED relay stays failed. */
	public void start()
	{
		if (state != State.IDLE)
			return;
		state = State.ALLOCATING;
		startedAt = clock.getAsLong();
		request(new Turn_Codec.Message(Turn_Codec.ALLOCATE, Turn_Codec.REQUEST, transaction()), null);
	}

	public State state()
	{
		return state;
	}

	/** The relayed address to offer the host - an ip:port on the relay - once ALLOCATED ; null otherwise. */
	public String relayed()
	{
		return state == State.ALLOCATED ? address : null;
	}

	/** This socket's mapping as the relay saw it : one more STUN sample. Null before the allocation. */
	public String mapped()
	{
		return mapped;
	}

	/** Why the relay FAILED, for a log or a row : "486 Allocation Quota Reached", "no answer in 5 s". */
	public String why()
	{
		return why;
	}

	/** True when this packet came from the relay server : its answers and Data indications are ours to read. */
	public boolean fromServer(Net_Peer from)
	{
		return from.equals(server);
	}

	/** The relay server's own address : the ip a host sees relayed checks come from. */
	public String server()
	{
		return server.address();
	}

	// ---------------------------------------------------------------- relayed peers

	/** True for a relayed peer's text. */
	public static boolean isRelayed(String address)
	{
		return address != null && address.startsWith(PREFIX);
	}

	/** The relayed peer that reaches this ip:port through the relay. Resolving sends nothing, like any transport's. */
	public Net_Peer peer(String address)
	{
		String text = isRelayed(address) ? address : PREFIX + address;
		return relayed.peer(text, Relayed_Peer::new);
	}

	/**
	 * Lets this peer's ip through the relay : nothing from an ip without one is relayed to us, and a Send to
	 * it is dropped by the relay. Safe to call again : a permission is asked once and refreshed by itself.
	 */
	public void permit(String address)
	{
		String target = isRelayed(address) ? address.substring(PREFIX.length()) : address;
		byte[] ip = Stun_Codec.ipOf(target);
		if (ip == null)
			return;
		String key = Stun_Codec.textOf(ip, 0);
		if (permissions.containsKey(key))
			return;
		permissions.put(key, Long.valueOf(Long.MIN_VALUE));
		if (state == State.ALLOCATED)
			askPermission(key);
	}

	/** Sends through the relay, to the peer's real address. Dropped (and counted) before it is allocated, or past {@link Turn_Codec#MAX_RELAYED}. */
	public void send(Net_Peer peer, ByteBuffer payload)
	{
		if (payload.remaining() > Turn_Codec.MAX_RELAYED)
		{
			tooBig++;
			throw new IllegalArgumentException(payload.remaining() + " bytes is past a relayed packet's " + Turn_Codec.MAX_RELAYED
					+ " B (MAX_PAYLOAD less the relay's framing)");
		}
		if (state != State.ALLOCATED)
		{
			payload.position(payload.limit());
			return;
		}
		Turn_Codec.Message send = new Turn_Codec.Message(Turn_Codec.SEND, Turn_Codec.INDICATION, transaction());
		send.peer = peer.address().substring(PREFIX.length());
		send.data = new byte[payload.remaining()];
		payload.get(send.data);
		shared.send(server, Turn_Codec.encode(send, null));
		sent++;
	}

	/** The relayed peers silent for their timeout, once each. */
	public List<Net_Peer> takeLost()
	{
		return relayed.takeLost();
	}

	// ---------------------------------------------------------------- the server

	/**
	 * A packet from the relay server. A Data indication is handed to the listener as from its relayed peer ;
	 * an answer moves the allocation on. Anything else is counted and dropped.
	 */
	public void received(ByteBuffer payload, Net_Listener listener)
	{
		Turn_Codec.Message message;
		ByteBuffer packet = payload.duplicate();
		try
		{
			message = Turn_Codec.decode(payload);
		}
		catch (Net_Rejected e)
		{
			rejected++;
			return;
		}
		if (message.is(Turn_Codec.DATA, Turn_Codec.INDICATION))
		{
			if (state != State.ALLOCATED)
				return;
			String text = PREFIX + message.peer;
			Net_Peer from = relayed.peer(text, Relayed_Peer::new);
			relayed.heard(text);
			arrived++;
			listener.received(from, ByteBuffer.wrap(message.data));
			return;
		}
		Request request = pending.remove(hex(message.transaction));
		if (request == null || message.method != request.message.method)
			return; // not ours, or too late
		// Once we hold a key, only an answer signed with it is believed : anyone can send a UDP packet from anywhere
		if (key != null && message.kind != Turn_Codec.ERROR && !Turn_Codec.authentic(packet, message, key))
		{
			rejected++;
			pending.put(hex(message.transaction), request);
			return;
		}
		long now = clock.getAsLong();
		if (message.kind == Turn_Codec.ERROR)
		{
			if ((message.error == 401 || message.error == 438) && message.nonce != null && message.realm != null && !request.retried)
			{
				// The first request goes without auth, and a nonce goes stale : both answers name what to sign with
				nonce = message.nonce;
				if (realm == null || !realm.equals(message.realm))
				{
					realm = message.realm;
					key = Turn_Codec.key(username, realm, password);
				}
				Turn_Codec.Message again = copy(request.message);
				request(again, request.permission).retried = message.error == 401;
				return;
			}
			if (request.message.method == Turn_Codec.CREATE_PERMISSION)
			{
				permissions.remove(request.permission);
				return; // a peer the relay refuses (403 : a private range) : that peer only
			}
			if (request.message.method == Turn_Codec.REFRESH && request.message.lifetime == 0)
				return;
			fail(message.error + " " + message.reason);
			return;
		}
		switch (message.method)
		{
			case Turn_Codec.ALLOCATE:
				if (message.relayed == null)
				{
					fail("an allocation with no relayed address");
					return;
				}
				address = message.relayed;
				mapped = message.mapped;
				state = State.ALLOCATED;
				allocatedAt = now;
				lifetimeMs = 1000L * (message.lifetime > 0 ? message.lifetime : LIFETIME_S);
				for (String ip : permissions.keySet())
					askPermission(ip);
				break;
			case Turn_Codec.REFRESH:
				allocatedAt = now;
				if (message.lifetime > 0)
					lifetimeMs = 1000L * message.lifetime;
				break;
			case Turn_Codec.CREATE_PERMISSION:
				if (permissions.containsKey(request.permission))
					permissions.put(request.permission, Long.valueOf(now));
				break;
			default:
				break;
		}
	}

	/** Retries, the allocation's refresh, the permissions' refresh. */
	public void update()
	{
		if (state == State.IDLE || state == State.FAILED || state == State.CLOSED)
			return;
		long now = clock.getAsLong();
		for (Iterator<Request> it = pending.values().iterator(); it.hasNext();)
		{
			Request request = it.next();
			if (now - request.first >= GIVE_UP_MS)
			{
				it.remove();
				if (request.message.method == Turn_Codec.CREATE_PERMISSION)
					permissions.put(request.permission, Long.valueOf(Long.MIN_VALUE / 2)); // asked again with the next refresh
				else
				{
					fail("no answer from the relay in " + GIVE_UP_MS / 1000 + " s");
					return;
				}
			}
			else if (now - request.last >= request.wait)
			{
				request.last = now;
				request.wait *= 2;
				shared.send(server, Turn_Codec.encode(request.message, key));
			}
		}
		if (state != State.ALLOCATED)
			return;
		if (now - allocatedAt >= lifetimeMs / 2 && !asking(Turn_Codec.REFRESH))
		{
			Turn_Codec.Message refresh = new Turn_Codec.Message(Turn_Codec.REFRESH, Turn_Codec.REQUEST, transaction());
			refresh.lifetime = LIFETIME_S;
			request(refresh, null);
		}
		for (Map.Entry<String, Long> permission : permissions.entrySet())
			if (permission.getValue().longValue() != Long.MIN_VALUE && now - permission.getValue().longValue() >= PERMISSION_REFRESH_MS)
				askPermission(permission.getKey());
	}

	/** Frees the allocation on the relay now, one copy : a lost one only means it lives out its lifetime. */
	public void close()
	{
		if (state == State.ALLOCATED)
		{
			Turn_Codec.Message free = new Turn_Codec.Message(Turn_Codec.REFRESH, Turn_Codec.REQUEST, transaction());
			free.lifetime = 0;
			sign(free);
			shared.send(server, Turn_Codec.encode(free, key));
		}
		state = State.CLOSED;
		pending.clear();
	}

	// ---------------------------------------------------------------- inside

	void askPermission(String ip)
	{
		permissions.put(ip, Long.valueOf(Long.MIN_VALUE));
		Turn_Codec.Message ask = new Turn_Codec.Message(Turn_Codec.CREATE_PERMISSION, Turn_Codec.REQUEST, transaction());
		ask.peer = ip; // "a.b.c.d:0" : the port is not looked at
		request(ask, ip);
	}

	boolean asking(int method)
	{
		for (Request request : pending.values())
			if (request.message.method == method)
				return true;
		return false;
	}

	Request request(Turn_Codec.Message message, String permission)
	{
		sign(message);
		Request request = new Request(message, permission, clock.getAsLong());
		pending.put(hex(message.transaction), request);
		shared.send(server, Turn_Codec.encode(message, key));
		return request;
	}

	/** Every request after the first carries the credential, with the nonce the relay last gave. */
	void sign(Turn_Codec.Message message)
	{
		if (key == null)
			return;
		message.username = username;
		message.realm = realm;
		message.nonce = nonce;
	}

	Turn_Codec.Message copy(Turn_Codec.Message old)
	{
		Turn_Codec.Message message = new Turn_Codec.Message(old.method, old.kind, transaction());
		message.peer = old.peer;
		message.lifetime = old.lifetime;
		return message;
	}

	void fail(String reason)
	{
		state = State.FAILED;
		why = reason;
		pending.clear();
	}

	byte[] transaction()
	{
		byte[] transaction = new byte[Stun_Codec.TRANSACTION];
		random.nextBytes(transaction);
		return transaction;
	}

	static String hex(byte[] bytes)
	{
		StringBuilder text = new StringBuilder();
		for (byte b : bytes)
			text.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		return text.toString();
	}

	static final class Request
	{
		final Turn_Codec.Message message;
		final String permission;
		final long first;
		long last, wait = RETRY_MS;
		boolean retried;

		Request(Turn_Codec.Message message, String permission, long now)
		{
			this.message = message;
			this.permission = permission;
			this.first = now;
			this.last = now;
		}
	}

	/** A peer reached through the relay : its text is {@link #PREFIX} and its real ip:port. */
	static final class Relayed_Peer implements Net_Peer
	{
		final String address;

		Relayed_Peer(String address)
		{
			this.address = address;
		}

		@Override
		public String address()
		{
			return address;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Relayed_Peer && ((Relayed_Peer) other).address.equals(address);
		}

		@Override
		public int hashCode()
		{
			return address.hashCode();
		}

		@Override
		public String toString()
		{
			return address;
		}
	}
}
