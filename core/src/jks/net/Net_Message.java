package jks.net;

/**
 * One packet of the game protocol, decoded : what {@link Net_Codec} turns into bytes and back.
 *
 * The protocol is phase 1.2 of docs/online-multiplayer.md. The shape is decided and this class
 * does not revisit it : clients send {@link Net_Input} at 60 Hz, the host sends {@link Net_Snapshot}
 * at 20 Hz, and the handful of control packets below say who is in. There is no reliability layer :
 * input repeats itself, a snapshot is replaced by the next one, and every control packet is safe to
 * send again until it is answered.
 *
 * Plain data, no sockets and no game objects : a session fills these and hands them to the codec,
 * and a browser build compiles them as they are.
 */
public abstract class Net_Message
{
	/** The byte on the wire. Never renumber one : bump {@link Net_Codec#VERSION} instead. */
	public enum Type
	{
		HELLO, WELCOME, JOIN, LEAVE, KEEPALIVE, INPUT, SNAPSHOT;

		static Type of(int code)
		{
			Type[] all = values();
			return code >= 0 && code < all.length ? all[code] : null;
		}
	}

	public abstract Type type();

	/** Client to host, first : "I speak this protocol, let me in". The version byte is the question. */
	public static final class Hello extends Net_Message
	{
		@Override
		public Type type()
		{
			return Type.HELLO;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Hello;
		}

		@Override
		public int hashCode()
		{
			return Type.HELLO.ordinal();
		}
	}

	/**
	 * Host to client, the answer to HELLO : you are this player, and the simulation is at this tick.
	 * Under d5 -> A the player is the machine, so this is the only identity a client ever gets.
	 */
	public static final class Welcome extends Net_Message
	{
		public int player;
		public int tick;

		public Welcome()
		{
		}

		public Welcome(int player, int tick)
		{
			this.player = player;
			this.tick = tick;
		}

		@Override
		public Type type()
		{
			return Type.WELCOME;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Welcome && ((Welcome) other).player == player && ((Welcome) other).tick == tick;
		}

		@Override
		public int hashCode()
		{
			return player * 31 + tick;
		}
	}

	/** Client to host : put my hero in the run. The online form of the first key press (#input pack). */
	public static final class Join extends Net_Message
	{
		@Override
		public Type type()
		{
			return Type.JOIN;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Join;
		}

		@Override
		public int hashCode()
		{
			return Type.JOIN.ordinal();
		}
	}

	/** Either way : this player is gone, and why. A host refusing a client says so with one of these too. */
	public static final class Leave extends Net_Message
	{
		public enum Reason
		{
			/** They closed the game or went back to the menu. */
			QUIT,
			/** Nothing heard for the transport's timeout. */
			TIMEOUT,
			/** The host speaks another protocol version. */
			VERSION,
			/** The run has no seat left. */
			FULL,
			/** The host ended the run (no host migration : everyone goes back to the lobby). */
			HOST_ENDED;

			static Reason of(int code)
			{
				Reason[] all = values();
				return code >= 0 && code < all.length ? all[code] : null;
			}
		}

		public int player;
		public Reason reason;

		public Leave()
		{
		}

		public Leave(int player, Reason reason)
		{
			this.player = player;
			this.reason = reason;
		}

		@Override
		public Type type()
		{
			return Type.LEAVE;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Leave && ((Leave) other).player == player && ((Leave) other).reason == reason;
		}

		@Override
		public int hashCode()
		{
			return player * 31 + (reason == null ? 0 : reason.ordinal());
		}
	}

	/** Either way, when there is nothing else to say : a NAT mapping dies after about 30 s of silence. */
	public static final class Keepalive extends Net_Message
	{
		@Override
		public Type type()
		{
			return Type.KEEPALIVE;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Keepalive;
		}

		@Override
		public int hashCode()
		{
			return Type.KEEPALIVE.ordinal();
		}
	}
}
