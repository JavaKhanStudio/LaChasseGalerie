package jks.net;

/**
 * Where a desktop host takes browser tabs as players (r80) : a second {@link Net_Transport}, beside the
 * game's socket, whose peers are named {@link #PREFIX} and a number, and which can answer a tab's WebRTC
 * offer. jks.rtc.Transport_Rtc is the one behind it ; it lives in the rtc module, so this interface is how
 * jks.lobby - JDK-only, deployed without libGDX or libwebrtc - reaches it.
 *
 * A {@link jks.lobby.Lobby_Client} it is plugged into answers the offers the service hands its lobby, and
 * merges these peers into its game view : HostSession sees a tab like any joiner. A host with none (its
 * WebRTC natives did not load) still hosts desktop players, and leaves a tab's offer unanswered.
 */
public interface Net_Tabs extends Net_Transport
{
	/** How a tab's peer is named : "rtc/" and a number. The game view routes by it. */
	String PREFIX = "rtc/";

	/** One tab being answered, and then its channel. Read from the thread that pumps. */
	interface Tab
	{
		Net_Peer peer();

		/** This end's whole description, for the tab, or null while it is still being gathered. */
		String sdp();

		/** Why this tab cannot go on, or null. A failed tab's peer is reported lost. */
		String failure();

		/** True once the channel is open both ways. */
		boolean open();
	}

	/** Answers a tab's offer, as it came from the tab : a stranger's text, which may not parse. Never throws for it. */
	Tab answer(String offer);

	/** Whether the address names a peer of this kind. */
	static boolean isTab(String address)
	{
		return address.startsWith(PREFIX);
	}
}
