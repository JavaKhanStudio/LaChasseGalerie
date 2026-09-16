package jks.smoke;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jks.headless.Headless_Runner;
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
 *   java jks.smoke.Net_Procs                     the gate
 *   java jks.smoke.Net_Procs host [clients]      a host on an ephemeral port : prints PORT n
 *   java jks.smoke.Net_Procs client port name    a client for that many seconds
 */
public class Net_Procs
{
	static final int CLIENTS = 2, CLIENT_SECONDS = 8, DEADLINE_SECONDS = 60;
	static final long TICK_NANOS = 1_000_000_000L / 60;

	public static void main(String[] args) throws Exception
	{
		if (args.length > 0 && args[0].equals("host"))
			Headless_Runner.launch(1280, 720, runner -> host(runner, args.length > 1 ? Integer.parseInt(args[1]) : CLIENTS));
		else if (args.length > 0 && args[0].equals("client"))
			System.exit(client(Integer.parseInt(args[1]), args[2]));
		else
			System.exit(gate());
	}

	// ---------------------------------------------------------------- the parent

	static int gate() throws Exception
	{
		List<Process> processes = new ArrayList<Process>();
		CompletableFuture<Integer> port = new CompletableFuture<Integer>();
		Process host = start("host", port, "host", String.valueOf(CLIENTS));
		processes.add(host);
		int failures = 0;
		try
		{
			int hostPort = port.get(DEADLINE_SECONDS, TimeUnit.SECONDS);
			for (int i = 1; i <= CLIENTS; i++)
				processes.add(start("client" + i, null, "client", String.valueOf(hostPort), "client" + i));

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
		}
		finally
		{
			for (Process process : processes)
				process.destroyForcibly();
		}
		System.out.println(failures == 0 ? "PROCS ok : a host JVM and " + CLIENTS + " client JVMs played over UDP on 127.0.0.1" : "PROCS FAILED : " + failures + " process(es)");
		return failures == 0 ? 0 : 1;
	}

	/** This JVM again, same classpath and working directory, its output prefixed with the name. */
	static Process start(String name, CompletableFuture<Integer> port, String... args) throws Exception
	{
		List<String> command = new ArrayList<String>();
		command.add(ProcessHandle.current().info().command().orElse("java"));
		command.add("--enable-native-access=ALL-UNNAMED");
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(Net_Procs.class.getName());
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

		Thread pipe = new Thread(() ->
		{
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					if (port != null && line.startsWith("PORT "))
						port.complete(Integer.valueOf(line.substring(5).trim()));
					System.out.println(String.format("%-8s| ", name) + line);
				}
			}
			catch (Exception e)
			{
				// The process died : its exit value says so
			}
			if (port != null)
				port.completeExceptionally(new IllegalStateException(name + " exited before it printed its port"));
		}, name + "-output");
		pipe.setDaemon(true);
		pipe.start();
		return process;
	}

	// ---------------------------------------------------------------- the host

	static void host(Headless_Runner runner, int expected) throws Exception
	{
		GVars_Random.seed(1);
		runner.boot();

		List<HostSession.Seat> joined = new ArrayList<HostSession.Seat>();
		List<String> gone = new ArrayList<String>();
		try (Net_Transport transport = Transport_Udp.open())
		{
			HostSession host = new HostSession(transport, new Game_Simulation(), new HostSession.Events()
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
			System.out.println("PORT " + transport.localPort());
			System.out.flush();

			long deadline = System.nanoTime() + (DEADLINE_SECONDS - 5) * 1_000_000_000L;
			long next = System.nanoTime();
			while (gone.size() < expected)
			{
				if (System.nanoTime() > deadline)
					throw new IllegalStateException("the host gave up : " + joined.size() + " joined, " + gone);
				host.tick(runner::step);
				next = sleepUntil(next + TICK_NANOS);
			}
			if (joined.size() != expected)
				throw new IllegalStateException(joined.size() + " players joined, expected " + expected);
			System.out.println("HOST ok : " + expected + " players over UDP port " + transport.localPort() + ", " + host.tick() + " ticks, "
					+ host.snapshotsSent + " snapshots sent ; " + gone);
		}
	}

	// ---------------------------------------------------------------- a client

	static int client(int port, String name) throws Exception
	{
		try (Net_Transport transport = Transport_Udp.open())
		{
			ClientSession client = new ClientSession(transport, "127.0.0.1:" + port);
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
