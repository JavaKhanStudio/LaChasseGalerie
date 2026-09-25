package jks.amain;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;

import jks.camera.GVars_Camera;
import jks.lobby.Lobby_Client;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;
import jks.online.Game_Simulation;
import jks.online.HostSession;
import jks.sounds.GVars_AudioManager;
import jks.vars.FVars_Heart;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vue.models.Vue_Client;
import jks.vue.models.Vue_Game;
import jks.vue.models.Vue_Menu;

public class Main_Game extends ApplicationAdapter 
{

	/**
	 * Real time waiting to be simulated. The world advances in whole steps of FVars_Heart.step and
	 * never by the length of a frame, so a machine that draws 30 or 144 pictures a second plays the
	 * same run at the same speed — it just sees it more or less often.
	 */
	private float accumulator ;
	
	/**
	 * The most real time one frame may catch up on. Past this the world is simulated slowly rather
	 * than the step count spiralling: a stall, a breakpoint or a dragged window is time we give up
	 * on, not time we try to replay in one frame.
	 */
	private static final float maxCatchUp = 5 * FVars_Heart.step ;
	
	/**
	 * --host : the session that lets clients in and sends them the run. It wraps each step of a
	 * Vue_Game and nothing else : the menu has no world to read. It outlives a run : the score screen is
	 * still that Vue_Game, and a new run picked there is another one under the same session (d14). The keyboard and pads of this
	 * window still join through IKM_Game_*, on the same PlayerId numbering as the peers.
	 */
	private HostSession host ;
	private Net_Transport hostTransport ;
	/** The lobby it was opened from (Online play, r43), open as long as the server is : null for --host. */
	private Lobby_Client hostLobby ;
	private final Runnable step = () -> GVars_Heart.vue.update(FVars_Heart.step) ;

	@Override
	public void create () 
	{
		if(GVars_Heart.joinAddress != null)
		{
			GVars_Heart.changeVue(new Vue_Client(GVars_Heart.joinAddress)) ; 
			return ; 
		}
		
		GVars_Heart.changeVue(GVars_Heart.startAtMenu ? new Vue_Menu() : new Vue_Game()) ; 
		if(GVars_Heart.hostPort >= 0)
		{
			hostTransport = Transport_Udp.open(GVars_Heart.hostPort) ; 
			host = new HostSession(hostTransport, new Game_Simulation()) ; 
			Gdx.app.log("host", "letting clients in on UDP port " + GVars_Heart.hostPort);
		}
	}

	@Override
	public void render () 
	{
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		
		accumulator += Gdx.graphics.getDeltaTime() ;
		
		if(accumulator > maxCatchUp)
			accumulator = maxCatchUp ; 
		
		while(accumulator >= FVars_Heart.step)
		{
			// Start, picked in a lobby this window hosts (r43) : the session plays on the lobby's own socket
			if(host == null && GVars_Heart.lobbyHost != null)
				hostFromLobby() ;
			if(hostLobby != null)
				feedLobby() ;
			
			// Close the server, picked on the score screen (d14) : every peer is told while the run still
			// exists, and the run then ends into the menu like a local one
			if(host != null && !GVars_Heart.hosting)
				closeHost() ; 
			
			if(host != null && GVars_Heart.vue instanceof Vue_Game)
				host.tick(step);
			else
				step.run();
			accumulator -= FVars_Heart.step ; 
		}
		
		GVars_Heart.vue.render();
	}
	
	@Override
	public void resize(int width, int height)
	{
		GVars_Camera.resize(width, height);
		if(GVars_Interface.mainInterface != null)
			GVars_Interface.mainInterface.getViewport().update(width, height, true);
		if(GVars_Heart.vue != null)
			GVars_Heart.vue.resize(width, height);
	}
	
    @Override
	public void dispose() 
	{
    	GVars_AudioManager.StopAndDisposeMusic();
    	if(host != null)
    		closeHost() ; 
    }
    
    private void hostFromLobby()
    {
    	hostLobby = GVars_Heart.lobbyHost.lobby ; 
    	hostTransport = GVars_Heart.lobbyHost.socket ; 
    	GVars_Heart.lobbyHost = null ; 
    	host = new HostSession(hostLobby.game(), new Game_Simulation()) ; 
    	Gdx.app.log("host", "hosting lobby " + hostLobby.code() + ", seen by the service at " + hostLobby.publicAddress());
    }
    
    /**
     * The lobby stays listed through the runs, with this window's player and its seats as its count, and
     * the joiners it mirrors are dropped : the connectivity checks already started on them by themselves,
     * and a latecomer's HELLO is the session's to answer.
     */
    private void feedLobby()
    {
    	hostLobby.players(1 + host.seats().size()) ; 
    	hostLobby.takeJoiners() ; 
    }
    
    /** Tells every peer the run ended (HOST_ENDED), closes the lobby if there is one, and lets go of the socket. */
    private void closeHost()
    {
    	host.close();
    	if(hostLobby != null)
    		hostLobby.close();
    	hostLobby = null ; 
    	hostTransport.close();
    	host = null ; 
    	hostTransport = null ; 
    	GVars_Heart.hosting = false ; 
    	Gdx.app.log("host", "closed the server");
    }
}
