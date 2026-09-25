package jks.vue.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Align;

import jks.input.IKM_Menu_Keyboard;
import jks.input.IKM_Menu_XBoxController;
import jks.lobby.Lobby_Client;
import jks.lobby.Lobby_Ice;
import jks.lobby.Lobby_Tab;
import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;
import jks.online.ClientSession;
import jks.online.HostSession;
import jks.parralax.Enum_ColdNight;
import jks.parralax.GVars_Parralax;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vinterface.Menu_Focus;
import jks.vue.AVue_Model;

/**
 * Online play's lobby (phase 2.3, r43) : host a game and read its code out, or join one by its code or
 * from the list, and see - while people are still getting ready - which way each pair of machines will
 * talk : direct, relayed, or not at all, with the fix when it is not (docs/online-multiplayer.md section 9).
 *
 * ONE SOCKET, opened here and never a second (the trap in section 3) : the Lobby_Client is built on it,
 * and whatever session follows plays on the same one through {@link Lobby_Client#game()}. Hosting, Start
 * hands the lobby to Main_Game (GVars_Heart.lobbyHost), which builds its HostSession on it ; joining, a
 * ClientSession goes to the address the connectivity check answered from as soon as it answers, its HELLOs
 * wait at the host until Start, and the WELCOME is what moves this window into Vue_Client.
 *
 * Every screen is a Menu_Focus ring (d8) : a pad reaches everything but typing a code, which is why the
 * open lobbies are listed as choices too. The keyboard types a code wherever the focus is.
 *
 * IN A BROWSER TAB (r82, GVars_Heart.tabLobby set) the same screens with a {@link Lobby_Tab} instead of a
 * Lobby_Client : Open games and a code, no Host a game (d7 : a tab is a player). The socket is then the
 * tab's RTCDataChannel transport, and the session goes to the host once its channel is open.
 *
 * ASCII ONLY in every string here : the skin's fonts carry 98 glyphs and drop the rest without a word.
 */
public class Vue_Lobby extends AVue_Model
{
	private enum Mode {CHOOSE, HOSTING, JOINING}

	/** How often the list of open lobbies is asked for again while it is on screen : the lobby's own refresh. */
	private static final long BROWSE_MS = Lobby_Client.REFRESH_MS ;
	/** Lobbies listed as choices : more would push Back off a 720-line screen. */
	private static final int LISTED = 4 ;
	/**
	 * A joiner still on its lobby screen repeats JOIN every half second, and each copy reaches the host : one
	 * not heard of for this long has gone back to its menu, and its row goes.
	 */
	private static final long JOINER_GONE_MS = 5_000 ;

	private Stage stage ;
	private final Menu_Focus focus = new Menu_Focus() ;
	private IKM_Menu_XBoxController padListener ;

	private Net_Transport socket ;
	private Lobby_Client lobby ;
	/** In a browser tab, the lobby instead of {@link #lobby}, which is then null ; {@link #socket} is its offerer. */
	private Lobby_Tab tab ;
	/** Handed on to Main_Game or Vue_Client : this view no longer closes them. */
	private boolean handedOver ;

	private Mode mode = Mode.CHOOSE ;
	private float width, height ;
	private Label status, advice ;
	private Table rows ;

	// CHOOSE
	private final StringBuilder typed = new StringBuilder() ;
	private TextField codeField ;
	private TextButton joinButton ;
	private String listed = "" ;
	private long lastBrowse ;

	// HOSTING
	private Label codeLabel ;
	/** When each joiner was last mirrored to this host, by the address the service saw it at : its link's key. */
	private final Map<String, Long> joinerHeard = new HashMap<String, Long>() ;
	/** Joiners in the order they came, so Player 2 stays Player 2. */
	private final List<String> joinerOrder = new ArrayList<String>() ;
	private String shownRows = "" ;

	// JOINING
	private String joiningCode ;
	private ClientSession client ;
	private final Vue_Client.Relay relay = new Vue_Client.Relay() ;

	private static long now()
	{return System.nanoTime() / 1_000_000L ;}

	@Override
	public void init()
	{
		GVars_Interface.loadSkin();
		GVars_Parralax.init();
		GVars_Parralax.setPages(Enum_ColdNight.COLD_NIGHT, Enum_ColdNight.COLD_WATER) ;

		stage = GVars_Interface.menuStage() ;
		padListener = new IKM_Menu_XBoxController(focus) ;
		// The Stage first for the pointer, then the code being typed, then the arrows and Enter
		Gdx.input.setInputProcessor(new InputMultiplexer(stage, typing, new IKM_Menu_Keyboard(focus))) ;
		Controllers.clearListeners();
		Controllers.addListener(padListener) ;

		width = stage.getWidth() ;
		height = stage.getHeight() ;

		if(GVars_Heart.tabLobby != null)
		{
			tab = GVars_Heart.tabLobby.get() ;
			socket = tab.offerer() ;
		}
		else
		{
			socket = Transport_Udp.open() ;
			lobby = new Lobby_Client(socket, GVars_Heart.lobbyService, Vue_Lobby::now) ;
			// Once, while the screen opens : it resolves two names, which may take as long as a lookup does
			lobby.ice().probe(Lobby_Ice.STUN_SERVERS) ;
		}

		choose() ;
	}

	// ---------------------------------------------------------------- the three screens

	private void choose()
	{
		mode = Mode.CHOOSE ;
		Table table = start("Online play") ;

		// A tab is a player (d7) : it cannot host
		if(tab == null)
		{
			TextButton host = button("Host a game") ;
			focus.add(host, picker -> hostGame()) ;
			table.add(host).width(width * 0.34f).height(height * 0.09f).padBottom(height * 0.03f).row();
		}

		Label open = new Label("Open games", GVars_Interface.baseSkin, "default") ;
		table.add(open).padBottom(height * 0.01f).row();
		Lobby_Message.Listing listing = listing() ;
		if(listing == null || listing.rows.isEmpty())
		{
			String empty = tab == null ? "none yet - host one, or type a code" : "none yet - type a code" ;
			Label none = new Label(listing == null ? "asking the lobby service..." : empty, GVars_Interface.baseSkin, "default") ;
			none.getColor().a = 0.75f ;
			table.add(none).padBottom(height * 0.03f).row();
		}
		else
			for(int i = 0 ; i < Math.min(LISTED, listing.rows.size()) ; i++)
			{
				Lobby_Message.Row row = listing.rows.get(i) ;
				TextButton game = button(spaced(row.code) + "    " + row.players + "/" + row.seats) ;
				final String code = row.code ;
				focus.add(game, picker -> joinGame(code)) ;
				table.add(game).width(width * 0.34f).height(height * 0.07f).padBottom(height * 0.01f).row();
			}
		listed = listingKey(listing) ;

		Table code = new Table() ;
		codeField = new TextField(spaced(typed.toString()), GVars_Interface.baseSkin) ;
		codeField.setDisabled(true) ;
		codeField.setMessageText("type a code") ;
		codeField.setAlignment(Align.center) ;
		code.add(codeField).width(width * 0.2f).height(height * 0.07f).padRight(width * 0.01f) ;
		joinButton = button("Join code") ;
		focus.add(joinButton, picker -> joinGame(typed.toString())) ;
		code.add(joinButton).width(width * 0.13f).height(height * 0.07f) ;
		table.add(code).padTop(height * 0.02f).padBottom(height * 0.03f).row();

		back(table, picker -> GVars_Heart.changeVue(new Vue_Menu())) ;
		finish(table) ;
	}

	private void hostGame()
	{
		lobby.host(HostSession.MAX_PLAYERS) ;
		// Browser tabs too (r80), when this machine's WebRTC loads : a tab's row is its data channel's
		if(GVars_Heart.tabs != null && !lobby.takesTabs())
			lobby.tabs(GVars_Heart.tabs.get()) ;
		mode = Mode.HOSTING ;
		Table table = start("Your game") ;

		codeLabel = new Label("", GVars_Interface.baseSkin, "title") ;
		table.add(codeLabel).padBottom(height * 0.01f).row();
		Label tell = new Label("Give this code to your friends : they join it from Online play.", GVars_Interface.baseSkin, "default") ;
		table.add(tell).padBottom(height * 0.03f).row();

		rows = new Table() ;
		shownRows = null ;
		table.add(rows).width(width * 0.7f).padBottom(height * 0.03f).row();

		TextButton go = button("Start") ;
		// The hand that starts the run is in it, as from the menu (d9)
		focus.add(go, picker ->
		{
			handedOver = true ;
			GVars_Heart.lobbyHost = new GVars_Heart.Lobby_Host(lobby, socket) ;
			GVars_Heart.changeVue(new Vue_Game(picker)) ;
		}) ;
		table.add(go).width(width * 0.34f).height(height * 0.09f).padBottom(height * 0.02f).row();

		// Public by default, so a pad can join from Open games without typing (r76, Simon's pick). After
		// Start, so Start stays the first choice
		TextButton listed = button(listedText()) ;
		focus.add(listed, picker ->
		{
			lobby.unlisted(!lobby.unlisted()) ;
			listed.setText(listedText()) ;
		}) ;
		table.add(listed).width(width * 0.34f).height(height * 0.08f).padBottom(height * 0.02f).row();

		back(table, picker ->
		{
			lobby.close() ;
			joinerHeard.clear() ;
			joinerOrder.clear() ;
			for(Lobby_Ice.Link link : new ArrayList<Lobby_Ice.Link>(lobby.ice().links()))
				lobby.ice().forget(link.key) ;
			choose() ;
		}) ;
		finish(table) ;
	}

	private String listedText()
	{
		return lobby.unlisted() ? "Private : code only" : "Public : in Open games" ;
	}

	private void joinGame(String code)
	{
		String normal = Lobby_Codec.normalise(code) ;
		if(normal == null)
		{
			status.setText("A code is " + Lobby_Codec.CODE_LENGTH + " letters and digits.") ;
			return ;
		}
		if(tab != null)
			tab.join(normal) ;
		else
			lobby.join(normal) ;
		joiningCode = normal ;
		mode = Mode.JOINING ;
		Table table = start("Joining " + spaced(normal)) ;

		rows = new Table() ;
		shownRows = null ;
		table.add(rows).width(width * 0.7f).padBottom(height * 0.04f).row();

		back(table, picker ->
		{
			leaveJoin() ;
			choose() ;
		}) ;
		finish(table) ;
	}

	private void leaveJoin()
	{
		if(client != null)
			client.close() ;
		client = null ;
		joiningCode = null ;
		if(tab != null)
		{
			tab.stopJoining() ;
			return ;
		}
		lobby.stopJoining() ;
		if(lobby.joined() != null)
			lobby.ice().forget(lobby.joined().host.get(0)) ;
	}

	/** A new screen : the title, and the status and advice lines every screen has at the bottom. */
	private Table start(String title)
	{
		stage.clear() ;
		focus.clear() ;
		Table table = new Table() ;
		table.setFillParent(true);
		table.add(new Label(title, GVars_Interface.baseSkin, "title")).padBottom(height * 0.04f).row();
		return table ;
	}

	private void back(Table table, Menu_Focus.Taken action)
	{
		TextButton back = button("Back") ;
		focus.add(back, action) ;
		table.add(back).width(width * 0.34f).height(height * 0.08f).padBottom(height * 0.03f).row();
	}

	private void finish(Table table)
	{
		status = new Label("", GVars_Interface.baseSkin, "default") ;
		table.add(status).row();
		advice = new Label("", GVars_Interface.baseSkin, "default") ;
		advice.setWrap(true) ;
		advice.setAlignment(Align.center) ;
		advice.getColor().a = 0.85f ;
		table.add(advice).width(width * 0.7f).padTop(height * 0.01f).row();
		stage.addActor(table) ;
		showStatus() ;
	}

	private TextButton button(String text)
	{return new TextButton(text, GVars_Interface.baseSkin) ;}

	// ---------------------------------------------------------------- every frame

	@Override
	public void update(float delta)
	{
		if(focus.runPicked())
			return ;

		// A joiner's session pumps the shared socket, the lobby's packets with it ; before one, the lobby does.
		// A tab's lobby is its own WebSocket, pumped either way
		if(client != null)
			client.tick(0) ;
		if(tab != null)
			tab.pump() ;
		else if(client == null)
			lobby.pump() ;

		switch(mode)
		{
			case CHOOSE :
				if(now() - lastBrowse >= BROWSE_MS)
				{
					if(tab != null)
						tab.browse() ;
					else
						lobby.browse() ;
					lastBrowse = now() ;
				}
				// Rebuilt only when the list changed, or the focus would jump back to the top every refresh
				if(listing() != null && !listingKey(listing()).equals(listed))
					choose() ;
				break ;
			case HOSTING :
				updateHosting() ;
				break ;
			case JOINING :
				if(tab != null ? updateTabJoining() : updateJoining())
					return ;
				break ;
		}
		showStatus() ;

		GVars_Parralax.scroll(delta, 2f, 0);
		GVars_Parralax.act(delta);
		stage.act(delta);
	}

	private void updateHosting()
	{
		codeLabel.setText(lobby.code() == null ? "opening..." : spaced(lobby.code())) ;

		long now = now() ;
		for(List<String> joiner : lobby.takeJoiners())
		{
			String key = joiner.get(0) ;
			if(!joinerHeard.containsKey(key))
				joinerOrder.add(key) ;
			joinerHeard.put(key, now) ;
		}
		for(String key : new ArrayList<String>(joinerOrder))
			if(now - joinerHeard.get(key) > JOINER_GONE_MS)
			{
				joinerOrder.remove(key) ;
				joinerHeard.remove(key) ;
				lobby.ice().forget(key) ;
			}
		lobby.players(1 + joinerOrder.size() + lobby.tabRows().size()) ;

		List<String[]> lines = new ArrayList<String[]>() ;
		lines.add(new String[] {"You (host)", selfLine(), GVars_Heart.tabs != null && !lobby.takesTabs() ? "Browser players cannot join : WebRTC did not load on this machine." : null}) ;
		int number = 2 ;
		for(String key : joinerOrder)
		{
			Lobby_Ice.Link link = lobby.ice().link(key) ;
			lines.add(new String[] {"Player " + number++, routeLine(link), link == null ? null : link.advice()}) ;
		}
		// A tab has no Lobby_Ice link : its route is its own WebRTC connection's
		for(Lobby_Client.Tab browser : lobby.tabRows())
			lines.add(new String[] {"Player " + number++, tabLine(browser), browser.tab.failure() == null ? null : "The browser could not connect : " + browser.tab.failure()}) ;
		showRows(lines) ;
	}

	/** @return true when this view has handed over and must not touch its stage again this frame */
	private boolean updateJoining()
	{
		List<String[]> lines = new ArrayList<String[]>() ;
		Lobby_Message.Joined joined = lobby.joined() ;
		Lobby_Ice.Link link = joined == null ? null : lobby.ice().link(joined.host.get(0)) ;
		lines.add(new String[] {"Host", joined == null ? "asking the lobby service..." : routeLine(link), link == null ? null : link.advice()}) ;
		lines.add(new String[] {"You", selfLine(), null}) ;
		showRows(lines) ;

		// The game goes where the check got an answer (r42), or through the relay (r45), not to the first address the service gave
		if(client == null && link != null && link.usable())
			client = new ClientSession(lobby.game(), link.address(), Vue_Client.machineKey(), relay) ;
		if(client == null)
			return false ;

		switch(client.state())
		{
			case IN :
				lobby.stopJoining() ;
				handedOver = true ;
				GVars_Heart.changeVue(new Vue_Client(lobby, socket, client, relay)) ;
				return true ;
			case ENDED :
			case LOST :
				// The host went quiet, or said no : ask the check again, and a new session once it answers
				client = null ;
				return false ;
			default :
				return false ;
		}
	}

	/** {@link #updateJoining()} in a browser tab : the route is the tab's own WebRTC call to the host. */
	private boolean updateTabJoining()
	{
		List<String[]> lines = new ArrayList<String[]>() ;
		lines.add(new String[] {"Host", tabRoute(), tab.failure()}) ;
		lines.add(new String[] {"You", "in a browser", null}) ;
		showRows(lines) ;

		if(client == null && tab.state() == Lobby_Tab.State.OPEN)
			client = new ClientSession(socket, tab.peer().address(), Vue_Client.machineKey(), relay) ;
		if(client == null)
			return false ;

		switch(client.state())
		{
			case IN :
				// The WebSocket was only for signalling : the game plays on the channel alone
				tab.close() ;
				handedOver = true ;
				GVars_Heart.changeVue(new Vue_Client(null, socket, client, relay)) ;
				return true ;
			case ENDED :
			case LOST :
				// The host went quiet, or said no : a new call, and a new session once it opens
				client = null ;
				tab.join(joiningCode) ;
				return false ;
			default :
				return false ;
		}
	}

	private String tabRoute()
	{
		switch(tab.state())
		{
			case JOINING :
				return "asking the lobby service..." ;
			case OFFERING :
				return tab.call() != null && tab.call().sdp() == null ? "describing this browser..." : "waiting for the host to answer..." ;
			case CONNECTING :
				return "connecting over WebRTC..." ;
			case OPEN :
				return "WebRTC data channel open" ;
			case FAILED :
				return "cannot connect" ;
			default :
				return "" ;
		}
	}

	private Lobby_Message.Listing listing()
	{return tab != null ? tab.listing() : lobby.listing() ;}

	/** Rebuilt when a line changes : two labels a player, and the fix under a pair that needs one. */
	private void showRows(List<String[]> lines)
	{
		StringBuilder key = new StringBuilder() ;
		for(String[] line : lines)
			key.append(line[0]).append('|').append(line[1]).append('|').append(line[2]).append('\n') ;
		if(key.toString().equals(shownRows))
			return ;
		shownRows = key.toString() ;

		rows.clear() ;
		for(String[] line : lines)
		{
			rows.add(new Label(line[0], GVars_Interface.baseSkin, "default")).left().width(width * 0.2f) ;
			rows.add(new Label(line[1], GVars_Interface.baseSkin, "default")).left().expandX().row() ;
			if(line[2] != null)
			{
				Label fix = new Label(line[2], GVars_Interface.baseSkin, "default") ;
				fix.setWrap(true) ;
				fix.setColor(1f, 0.85f, 0.55f, 1f) ;
				rows.add() ;
				rows.add(fix).left().width(width * 0.5f).padBottom(height * 0.01f).row() ;
			}
		}
	}

	/** What this machine's own network looks like, in words a player can act on. */
	private String selfLine()
	{
		if(lobby.ice().probing())
			return "testing your connection..." ;
		switch(lobby.ice().nat())
		{
			case OPEN :
			case EASY :
				return "ready" ;
			case HARD :
				return lobby.ice().ipv6() ? "ready over IPv6, harder over IPv4" : "hard to reach" ;
			default :
				return lobby.ice().ipv6() ? "ready over IPv6" : "not tested" ;
		}
	}

	private static String routeLine(Lobby_Ice.Link link)
	{
		if(link == null)
			return "checking the connection..." ;
		switch(link.route())
		{
			case CHECKING :
				return "checking the connection..." ;
			case DIRECT :
				return "direct" + (link.settledMs() >= 0 ? " (found in " + link.settledMs() + " ms)" : "") ;
			case RELAYED :
				return "relayed" ;
			case CANNOT_CONNECT :
				return "cannot connect" ;
			case RECONNECTING :
				return "reconnecting..." ;
			default :
				return "" ;
		}
	}

	private static String tabLine(Lobby_Client.Tab tab)
	{
		if(tab.tab.failure() != null)
			return "browser : cannot connect" ;
		if(!tab.answered())
			return "browser : answering..." ;
		return tab.tab.open() ? "browser : WebRTC data channel open" : "browser : connecting over WebRTC..." ;
	}

	/** The line under every screen : what the service said, then what this machine can fix. */
	private void showStatus()
	{
		String text = "" ;
		if(tab != null ? tab.serviceOutdated() >= 0 : lobby.serviceOutdated() >= 0)
			text = "This game is out of date : update it to play online." ;
		else if(tab != null ? tab.serviceLost() : lobby.serviceLost())
			text = "The lobby service" + (tab == null ? " at " + GVars_Heart.lobbyService : "") + " does not answer." ;
		else if(mode == Mode.JOINING && joiningCode != null && (tab != null ? tab.refused() : lobby.refused()) != null)
			text = refusal(tab != null ? tab.refused() : lobby.refused()) ;
		else if(mode == Mode.JOINING && client != null)
			text = "Waiting for the host to start." ;
		else if(mode == Mode.HOSTING && lobby.code() == null)
			text = "Opening your game..." ;
		status.setText(text) ;

		String fix = tab != null ? null : lobby.ice().advice() ;
		advice.setText(fix == null ? "" : fix) ;
	}

	private static String refusal(Lobby_Message.Refused no)
	{
		switch(no.reason)
		{
			case NO_SUCH_LOBBY :
				return "No open game has that code : check it, or the host may have closed it." ;
			case VERSION :
				return "That game runs another version of La chasse-galerie : you both need the same one." ;
			case FULL :
				return "That game is full." ;
			default :
				return "The lobby service is full : try again in a minute." ;
		}
	}

	private static String listingKey(Lobby_Message.Listing listing)
	{
		if(listing == null)
			return "" ;
		StringBuilder key = new StringBuilder("listed:") ;
		for(Lobby_Message.Row row : listing.rows)
			key.append(row.code).append(row.players).append('/').append(row.seats).append(' ') ;
		return key.toString() ;
	}

	/** ABC DEF : six letters are read out loud more easily in two threes. */
	static String spaced(String code)
	{return code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code ;}

	/** Letters and digits of the code alphabet go into the code wherever the focus is ; Backspace takes one out. */
	private final InputAdapter typing = new InputAdapter()
	{
		@Override
		public boolean keyTyped(char character)
		{
			if(mode != Mode.CHOOSE)
				return false ;
			char upper = Character.toUpperCase(character) ;
			if(Lobby_Codec.CODE_ALPHABET.indexOf(upper) < 0 || typed.length() >= Lobby_Codec.CODE_LENGTH)
				return false ;
			typed.append(upper) ;
			codeField.setText(spaced(typed.toString())) ;
			// A whole code : Enter joins it
			if(typed.length() == Lobby_Codec.CODE_LENGTH)
				focus.focus(joinButton) ;
			return true ;
		}

		@Override
		public boolean keyDown(int keycode)
		{
			if(mode != Mode.CHOOSE)
				return false ;
			if(keycode == Keys.BACKSPACE && typed.length() > 0)
			{
				typed.setLength(typed.length() - 1) ;
				codeField.setText(spaced(typed.toString())) ;
				return true ;
			}
			if(keycode == Keys.V && (Gdx.input.isKeyPressed(Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Keys.CONTROL_RIGHT)))
			{
				String pasted = Lobby_Codec.normalise(Gdx.app.getClipboard().getContents()) ;
				if(pasted != null)
				{
					typed.setLength(0) ;
					typed.append(pasted) ;
					codeField.setText(spaced(pasted)) ;
					focus.focus(joinButton) ;
				}
				return true ;
			}
			return false ;
		}
	} ;

	@Override
	public void resize(int width, int height)
	{stage.getViewport().update(width, height, true) ;}

	@Override
	public void render()
	{
		Gdx.gl.glClearColor(0, 0, 0, 1);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

		GVars_Parralax.background.render();
		GVars_Parralax.foreground.render();

		stage.getViewport().apply();
		stage.draw();
	}

	@Override
	public void dispose()
	{
		Controllers.removeListener(padListener);
		stage.dispose();
		if(handedOver)
			return ;
		// Back to the menu, or the window closing : the lobby this machine hosts is closed, and the socket
		if(client != null)
			client.close() ;
		if(tab != null)
			tab.close() ;
		else
			lobby.close() ;
		socket.close() ;
	}
}
