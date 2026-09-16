package jks.smoke;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jks.headless.Headless_Runner;
import jks.lobby.Lobby_Client;
import jks.lobby.Lobby_Ice;
import jks.net.Net_Input;
import jks.net.Net_Message;
import jks.net.Net_Snapshot;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;
import jks.online.ClientSession;
import jks.online.Game_Simulation;
import jks.online.HostSession;
import jks.vars.GVars_Random;

/**
 * `./gradlew netprocs` : phase 1.4 across processes (r39). One JVM runs the real game headless behind a
 * HostSession on a UDP port the OS picks ; two more JVMs are ClientSessions and nothing else - they never
 * load the game, a texture or Box2D - and reach it over 127.0.0.1, in real time at 60 Hz.
 *
 * No process can see another's memory, so each holds what it can see to account :
 * <ul>
 * <li>a client : it was let in, a hero of its own appeared, about 20 snapshots a second arrived, and the
 *     hero it draws walks the way it presses - right while it holds RIGHT, left while it holds LEFT ;</li>
 * <li>the host : two players came in, their frames and presses landed, and both left by quitting.</li>
 * </ul>
 * The parent starts them, prefixes their output, and fails if any of them does or if they overrun.
 *
 * `./gradlew netlobby` is the same game found through the lobby service (phase 2.1, r41) : a fourth JVM
 * runs jks.lobby.Lobby_Main on the lobby module's classpath, which has NO libGDX on it ; the host opens a
 * lobby on the socket it plays on, and the clients know nothing but the service's address and the code.
 * On top of the above, each client plays through the address its connectivity check (r42) got an answer
 * from, the host holds each player it let in to have come from an address the service mirrored to it,
 * and the parent holds the service to have logged both joins and the host closing.
 *
 *   java jks.smoke.Net_Procs [lobby]                          the gate, direct or through a lobby
 *   java jks.smoke.Net_Procs host clients [service]           a host : prints PORT n, or CODE c once the service opened its lobby
 *   java jks.smoke.Net_Procs client port|service/CODE name    a client for that many seconds
 */
public class Net_Procs
{
	static final int CLIENTS = 2, CLIENT_SECONDS = 8, DEADLINE_SECONDS = 60;
	static final long TICK_NANOS = 1_000_000_000L / 60;

	public static void main(String[] args) throws Exception
	{
		if (args.length > 0 && args[0].equals("host"))
			Headless_Runner.launch(1280, 720, runner -> host(runner, args.length > 1 ? Integer.parseInt(args[1]) : CLIENTS, args.length > 2 ? args[2] : null));
		else if (args.length > 0 && args[0].equals("client"))
			System.exit(client(args[1], args[2]));
		else
			System.exit(gate(args.length > 0 && args[0].equals("lobby")));
	}

	// ---------------------------------------------------------------- the parent

	static int gate(boolean viaLobby) throws Exception
	{
		List<Process> processes = new ArrayList<Process>();
		List<String> serviceLog = Collections.synchronizedList(new ArrayList<String>());
		Process service = null;
		int failures = 0;
		String code = null;
		try
		{
			String lobby = null;
			if (viaLobby)
			{
				String classpath = System.getProperty("lobby.classpath");
				if (classpath == null)
					throw new IllegalStateException("-Dlobby.classpath is not set : run it as ./gradlew netlobby");
				CompletableFuture<String> servicePort = new CompletableFuture<String>();
				// Port 0 : the gate must not depend on a free fixed port. A deployed service passes one
				service = start("lobby", servicePort, "PORT ", serviceLog, java(classpath, "jks.lobby.Lobby_Main", "0"));
				lobby = "127.0.0.1:" + servicePort.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
			}

			CompletableFuture<String> ready = new CompletableFuture<String>();
			Process host = viaLobby
					? start("host", ready, "CODE ", null, java(System.getProperty("java.class.path"), Net_Procs.class.getName(), "host", String.valueOf(CLIENTS), lobby))
					: start("host", ready, "PORT ", null, java(System.getProperty("java.class.path"), Net_Procs.class.getName(), "host", String.valueOf(CLIENTS)));
			processes.add(host);
			String target = ready.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
			if (viaLobby)
			{
				code = target;
				target = lobby + "/" + code;
			}
			for (int i = 1; i <= CLIENTS; i++)
				processes.add(start("client" + i, null, null, null, java(System.getProperty("java.class.path"), Net_Procs.class.getName(), "client", target, "client" + i)));

			long deadline = System.currentTimeMillis() + DEADLINE_SECONDS * 1000L;
			for (Process process : processes)
			{
				long left = Math.max(1, deadline - System.currentTimeMillis());
				if (!process.waitFor(left, TimeUnit.MILLISECONDS))
				{
					System.out.println("PROCS a process overran " + DEADLINE_SECONDS + " s");
					failures++;
				}
				else if (process.exitValue() != 0)
					failures++;
			}

			if (viaLobby)
			{
				String closed = "CLOSED " + code + " : closed";
				for (int i = 0; i < 200 && !serviceLog.contains(closed); i++)
					Thread.sleep(10);
				int joins = 0;
				synchronized (serviceLog)
				{
					for (String line : serviceLog)
						if (line.startsWith("JOINING " + code))
							joins++;
				}
				if (!service.isAlive())
				{
					System.out.println("PROCS the lobby service died");
					failures++;
				}
				if (joins < CLIENTS)
				{
					System.out.println("PROCS the service mirrored " + joins + " joins to " + code + ", expected at least " + CLIENTS);
					failures++;
				}
				if (!serviceLog.contains(closed))
				{
					System.out.println("PROCS the service never logged the host closing " + code);
					failures++;
				}
			}
		}
		finally
		{
			for (Process process : processes)
				process.destroyForcibly();
			if (service != null)
				service.destroyForcibly();
		}
		String what = viaLobby ? "a lobby service JVM with no libGDX, a host JVM and " + CLIENTS + " client JVMs that knew only the code"
				: "a host JVM and " + CLIENTS + " client JVMs";
		System.out.println(failures == 0 ? "PROCS ok : " + what + " played over UDP on this machine" : "PROCS FAILED : " + failures + " problem(s)");
		return failures == 0 ? 0 : 1;
	}

	static List<String> java(String classpath, String main, String... args)
	{
		List<String> command = new ArrayList<String>();
		command.add(ProcessHandle.current().info().command().orElse("java"));
		command.add("--enable-native-access=ALL-UNNAMED");
		command.add("-cp");
		command.add(classpath);
		command.add(main);
		command.addAll(List.of(args));
		return command;
	}

	/** Starts the command, its output prefixed with the name ; completes the future with what follows the first line starting with the prefix. */
	static Process start(String name, CompletableFuture<String> value, String prefix, List<String> lines, List<String> command) throws Exception
	{
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

		Thread pipe = new Thread(() ->
		{
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (value != null && line.startsWith(prefix))
						value.complete(line.substring(prefix.length()).trim());
					if (lines != null)
						lines.add(line);
					System.out.println(String.format("%-8s| ", name) + line);
				}
			}
			catch (Exception e)
			{
				// The process died : its exit value says so
			}
			if (value != null)
				value.completeExceptionally(new IllegalStateException(name + " exited before it printed " + prefix.trim()));
		}, name + "-output");
		pipe.setDaemon(true);
		pipe.start();
		return process;
	}

	// ---------------------------------------------------------------- the host

	static void host(Headless_Runner runner, int expected, String service) throws Exception
	{
		GVars_Random.seed(1);
		runner.boot();

		List<HostSession.Seat> joined = new ArrayList<HostSession.Seat>();
		List<String> gone = new ArrayList<String>();
		Set<String> mirrored = new LinkedHashSet<String>();
		try (Net_Transport transport = Transport_Udp.open())
		{
			// ONE socket : the lobby is talked to on the transport the game plays on, and the session gets the view without its packets
			Lobby_Client lobby = service == null ? null : new Lobby_Client(transport, service, () -> System.nanoTime() / 1_000_000L);
			HostSession host = new HostSession(lobby == null ? transport : lobby.game(), new Game_Simulation(), new HostSession.Events()
			{
				@Override
				public void joined(HostSession.Seat seat)
				{
					if (!joined.contains(seat))
						joined.add(seat);
				}

				@Override
				public void left(HostSession.Seat seat, Net_Message.Leave.Reason reason)
				{
					gone.add(seat.player + " " + reason + " after " + seat.framesApplied + " frames and " + seat.pressesApplied + " presses");
					if (reason != Net_Message.Leave.Reason.QUIT)
						throw new IllegalStateException(seat.player + " left " + reason + ", not by quitting");
					if (seat.framesApplied < 5 * 60 || seat.pressesApplied < 5)
						throw new IllegalStateException(seat.player + "'s input did not land : " + seat.framesApplied + " frames, " + seat.pressesApplied + " presses");
				}
			});
			if (lobby == null)
			{
				System.out.println("PORT " + transport.localPort());
				System.out.flush();
			}
			else
				lobby.host(HostSession.MAX_PLAYERS);
			boolean announced = false;

			long deadline = System.nanoTime() + (DEADLINE_SECONDS - 5) * 1_000_000_000L;
			long next = System.nanoTime();
			while (gone.size() < expected)
			{
				if (System.nanoTime() > deadline)
					throw new IllegalStateException("the host gave up : " + joined.size() + " joined, " + gone);
				host.tick(runner::step);
				if (lobby != null)
				{
					lobby.players(host.seats().size());
					for (List<String> joiner : lobby.takeJoiners())
						mirrored.addAll(joiner);
					if (!announced && lobby.code() != null)
					{
						announced = true;
						System.out.println("lobby open, the service sees this host at " + lobby.publicAddress());
						System.out.println("CODE " + lobby.code());
						System.out.flush();
					}
				}
				next = sleepUntil(next + TICK_NANOS);
			}
			if (joined.size() != expected)
				throw new IllegalStateException(joined.size() + " players joined, expected " + expected);
			if (lobby != null)
			{
				for (HostSession.Seat seat : joined)
					if (!mirrored.contains(seat.peer.address()))
						throw new IllegalStateException(seat.player + " played from " + seat.peer.address() + ", not from an address the service mirrored : " + mirrored);
				System.out.println("every player came from an address the service mirrored : " + mirrored);
				for (Lobby_Ice.Link row : lobby.ice().links())
					System.out.println("lobby row " + row + (row.advice() == null ? "" : " : " + row.advice()));
				lobby.close();
			}
			System.out.println("HOST ok : " + expected + " players over UDP port " + transport.localPort() + ", " + host.tick() + " ticks, "
					+ host.snapshotsSent + " snapshots sent ; " + gone);
		}
	}

	// ---------------------------------------------------------------- a client

	static int client(String target, String name) throws Exception
	{
		try (Net_Transport transport = Transport_Udp.open())
		{
			ClientSession client;
			Lobby_Client lobby = null;
			if (target.contains("/"))
			{
				// Only the service and a code : where the host is comes from the lobby, on the socket the game will use
				lobby = new Lobby_Client(transport, target.substring(0, target.indexOf('/')), () -> System.nanoTime() / 1_000_000L);
				lobby.join(target.substring(target.indexOf('/') + 1));
				long deadline = System.nanoTime() + 10_000_000_000L;
				while (lobby.joined() == null)
				{
					if (System.nanoTime() > deadline || lobby.refused() != null)
						throw new IllegalStateException(name + " could not join " + target + " : " + (lobby.refused() == null ? "no answer" : lobby.refused().reason));
					lobby.pump();
					Thread.sleep(5);
				}
				System.out.println(name + " : the service says the host is at " + lobby.joined().host + ", and this client at " + lobby.publicAddress());
				// The game goes where the connectivity check got an answer (r42), not to the first address it was told
				Lobby_Ice.Link row = lobby.ice().link(lobby.joined().host.get(0));
				while (row.route() != Lobby_Ice.Route.DIRECT)
				{
					if (System.nanoTime() > deadline + Lobby_Ice.GIVE_UP_MS * 1_000_000L || row.route() == Lobby_Ice.Route.CANNOT_CONNECT)
						throw new IllegalStateException(name + " cannot reach the host : " + row + " : " + row.advice());
					lobby.pump();
					Thread.sleep(5);
				}
				System.out.println(name + " : the check answered in " + row.settledMs() + " ms, playing via " + row.address() + " of " + row.addresses());
				client = new ClientSession(lobby.game(), row.address());
			}
			else
				client = new ClientSession(transport, "127.0.0.1:" + target);
			int ticks = 0, inTicks = 0, heroTicks = 0;
			int withPress = 0, againstPress = 0;
			int lastHero = -1;
			float lastX = 0;
			long next = System.nanoTime();
			while (inTicks < CLIENT_SECONDS * 60)
			{
				if (ticks++ > DEADLINE_SECONDS * 60)
					throw new IllegalStateException(name + " never got in : " + client.state());
				if (client.state() == ClientSession.State.ENDED || client.state() == ClientSession.State.LOST)
					throw new IllegalStateException(name + " was dropped : " + client.state() + " " + client.endedBecause());

				// A second to the right, a second to the left, and a jump now and then
				int held = inTicks % 120 < 60 ? Net_Input.RIGHT : Net_Input.LEFT;
				int buttons = held | (inTicks % 45 == 20 ? Net_Input.JUMP : 0);
				if (client.state() == ClientSession.State.IN)
				{
					if (lobby != null)
						lobby.stopJoining();
					inTicks++;
					if (!client.hasHeroInNewest())
						client.join();
				}
				client.tick(buttons);

				Net_Snapshot.Hero mine = null;
				for (Net_Snapshot.Hero hero : client.view().heroes())
					if (hero.player == client.player())
						mine = hero;
				if (mine != null)
				{
					heroTicks++;
					// The picture is about 10 ticks behind the buttons : judge the middle of each second only
					int phase = inTicks % 120;
					boolean settled = phase > 25 && phase < 60 || phase > 85;
					if (mine.id == lastHero && settled && Math.abs(mine.x - lastX) > 1e-3f)
					{
						boolean right = mine.x > lastX;
						if (right == (held == Net_Input.RIGHT))
							withPress++;
						else
							againstPress++;
					}
					lastHero = mine.id;
					lastX = mine.x;
				}
				next = sleepUntil(next + TICK_NANOS);
			}

			float snapshotsPerSecond = client.snapshotsReceived / (float) CLIENT_SECONDS;
			client.close();
			System.out.println(name + " : player " + client.player() + ", " + client.snapshotsReceived + " snapshots (" + snapshotsPerSecond + "/s), hero drawn "
					+ heroTicks + " ticks, walked with the press " + withPress + " ticks and against it " + againstPress);
			if (client.player() == 0)
				throw new IllegalStateException(name + " was never welcomed");
			if (snapshotsPerSecond < 15 || snapshotsPerSecond > 21)
				throw new IllegalStateException(name + " : " + snapshotsPerSecond + " snapshots a second, the host sends 20");
			if (heroTicks < 5 * 60)
				throw new IllegalStateException(name + "'s own hero was drawn for only " + heroTicks + " ticks");
			if (withPress < 60 || withPress < 3 * againstPress)
				throw new IllegalStateException(name + "'s hero does not walk the way it presses : with " + withPress + ", against " + againstPress);
			System.out.println(name + " ok");
			return 0;
		}
		catch (Exception e)
		{
			e.printStackTrace(System.out);
			return 1;
		}
	}

	static long sleepUntil(long due) throws InterruptedException
	{
		long wait = due - System.nanoTime();
		if (wait > 0)
			Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
		// Fell more than a tick behind : do not try to catch up in a burst
		return Math.max(due, System.nanoTime() - TICK_NANOS);
	}
}
