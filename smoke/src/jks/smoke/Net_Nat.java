package jks.smoke;

import java.nio.ByteBuffer;

import jks.lobby.Lobby_Ice;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Transport;
import jks.net.Stun_Codec;
import jks.net.Transport_Udp;

/**
 * `./gradlew netnat` : what this machine's network looks like to the game, against the real internet (r42).
 * One UDP socket, the addresses it would offer, and the RFC 5780 probe against the public STUN servers the
 * game uses - with the verdict and the advice a lobby would show. Needs the internet, so it is not part of
 * nettest ; run it on the network you want to know about (a phone hotspot is the interesting one).
 */
public class Net_Nat
{
	public static void main(String[] args) throws Exception
	{
		try (Net_Transport transport = Transport_Udp.open())
		{
			// No lobby service here : the probe's own two servers are the samples
			Lobby_Ice ice = new Lobby_Ice(transport, () -> System.nanoTime() / 1_000_000L, new java.security.SecureRandom(), () -> null);
			System.out.println("NAT socket on port " + transport.localPort() + ", offering " + transport.localAddresses());
			long started = System.nanoTime();
			ice.probe(Lobby_Ice.STUN_SERVERS);
			long resolved = System.nanoTime();
			while (ice.probing())
			{
				transport.pump(new Net_Listener()
				{
					@Override
					public void received(Net_Peer from, ByteBuffer payload)
					{
						if (Stun_Codec.isStunPacket(payload))
							ice.received(from, payload);
					}
				});
				ice.update();
				Thread.sleep(1);
			}
			long done = System.nanoTime();
			System.out.println("NAT asked " + Lobby_Ice.STUN_SERVERS + " : names resolved in " + (resolved - started) / 1_000_000 + " ms, answers in "
					+ (done - resolved) / 1_000_000 + " ms");
			System.out.println("NAT they see this socket at " + ice.mapped());
			System.out.println("NAT mapping " + ice.nat() + (ice.carrierNat() ? ", carrier NAT" : "") + (ice.ipv6() ? ", global IPv6" : ", no global IPv6"));
			System.out.println("NAT advice : " + (ice.advice() == null ? "none, nothing to fix" : ice.advice()));
		}
	}
}
