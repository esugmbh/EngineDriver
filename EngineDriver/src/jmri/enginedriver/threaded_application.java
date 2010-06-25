/*Copyright (C) 2010 Jason M'Sadoques
  jlyonm@gmail.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

/* Version 0.3 - changes/additions by mstevetodd
 *   function labels for throttle, hiding unused
 *   separate rev-stop-fwd buttons
 *   read responses from withrottle and store in shared app variables
 *   forward responses to other activities, so they can update as needed
 *   advance screens only upon successful responses
 *   release loco when leaving throttle activity, with pref for stopping loco
 *   screen and messaging additions and edits to provide more feedback to user
 *   set short timeout on socket open and reads to prevent app from "hanging" on long timeout
 *   added heartbeat logic (when set on withrottle side)
 *   copy function labels from roster (note: they do not include sticky bit)
 *   send hardware address as HU<throttle-name>  (prevents duplicates in WiThrottle server)
 *   changed process namespace from net.lnxgfx to jmri.enginedriver
 *   added html About page
 */

/* Version 0.5 - changes/additions by mstevetodd
 *  added select loco button to ed screen, call ed direct from connect
 *  added 29 function buttons for both throttles, using scrollers
 *  disable buttons and slider and shrink screen usage for unselected loco
 *  adjust function buttons to indicate current state from WiT server
 *  added release buttons to sl activity
 */

/* Version 0.6 - changes/additions by mstevetodd
 *  added preference for Maximum Throttle, to set a maximum speed to be sent
 *  lowered minSDK from 7 to 4 (to allow use by Android 1.6 devices) 
 */

/*
 *   TODO: add consisting features
 *   TODO: add turnout controls
 *   TODO: add route controls
 *   TODO: toast messages on release of loco and update of preferences
 *   TODO: make private stuff private
 * threaded_application
 *   TODO: Move wifi listener to OnStart from OnCreate (so it works each time activity gets focus), and add OnPause (or somesuch) to turn off listener
 *   TODO: don't add discovered server more than once (restart WiT to see this)
 *   TODO: rewrite readTimer logic, to start back up rather than creating a new one
 *   TODO: add client-side conversation logging for easier debugging
 *   TODO: check for error on send and do something with it 
 *   TODO: improve error handling of read error (sometimes it loops sending toast messages) 
 *   TODO: move socket timeout values to preference
 *   TODO: determine why ip by server name won't resolve
 *   TODO: redo hard-coded 29 in function arrays
 *   TODO: split listener creation into more try blocks for better error handling
 * engine_driver:
 *   TODO: add graphics (slider, stop, directions, functions?)  add colors
 *   TODO: add throttle name to title bar
 *   TODO: in ed exit, don't send release if "Not Set"
 *   TODO: get 2nd line of label text working again
 *   TODO: unset all states when loco not selected
 *   TODO: allow for different button arrangements for each loco
 * select_loco:
 *   TODO: don't show or allow entry of loco if already in use on "other" throttle
 *   TODO: add "Select from Roster" list
 *   TODO: simplify select_loco by removing handler
 * preferences:
 *   TODO: show error if invalid entry
 *
 * These require changes to WiThrottle
 *   TODO: get current status (speed, direction, speed steps?)  On request would be best.
 *   TODO: add "available" roster/address list to select_loco_activity (need "in use" indicator from WiT)
 *   TODO: disallow "steal"  (if requested addr "in use", return error)  probably should be a WiT pref
 *   TODO: pull more details from roster
 *   TODO: add turnout, route, and power controls
 *   
 * Other potential changes to WiThrottle:
 *   ) remove throttle from withrottle screen on loss of connection (estop)
 *   ) add response for heartbeat (so client will know it's still alive)  status message would be ideal
 *   ) allow restart of withrottle (variable UI needs to be cleared when it closes)
 *   ) fix read error looping on loss of connection to device
 *   ) add "E"rror response
 * 
 * */

package jmri.enginedriver;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.net.*;
import java.io.*;

import android.util.Log;

import javax.jmdns.*;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager.MulticastLock;
import java.net.InetAddress;
import java.util.Timer;
import java.util.TimerTask;

import jmri.enginedriver.message_type;
import jmri.enginedriver.threaded_application.comm_thread.comm_handler;

import android.content.Context;
import android.content.SharedPreferences;

//The application will start up a thread that will handle network communication in order to ensure that the UI is never blocked.
//This thread will only act upon messages sent to it. The network communication needs to persist across activities, so that is why
public class threaded_application extends Application
{
    public comm_thread thread;
	String host_ip; //The IP address of the WiThrottle server.
	int port; //The TCP port that the WiThrottle server is running on
	int loco_address_T = -1; //The Address of the locomotive being controlled 
	int loco_address_S = -1; //The Address of the locomotive being controlled 
	//shared variables returned from the withrottle server, stored here for easy access by other activities
	String host_name_string; //retrieved host name of connection
	String loco_string_T = "Not Set"; //Loco Address string returned from the server for selected loco #1
	String loco_string_S = "Not Set"; //Loco Address string returned from the server for selected loco #1
	String withrottle_version_string; //version of withrottle server
	String roster_list_string; //roster list
	String roster_function_string_T; //roster function list for selected loco #1
	String roster_function_string_S; //roster function list for selected loco #1
	boolean[] function_states_T;  //current function states for first throttle
	boolean[] function_states_S;  //current function states for second throttle
	int heartbeat_interval; //heartbeat interval in seconds
	//Communications variables.
	Socket client_socket;
	InetAddress host_address;
	//For communication to the comm_thread.
	public comm_handler comm_msg_handler;
	//For communication back to the main UI activity.
	public Handler ui_msg_handler;
	//For communication to the engine driver activity
	public Handler engine_driver_msg_handler;
	//For communication to the select loco activity
	public Handler select_loco_msg_handler;
	PrintWriter output_pw;
	BufferedReader input_reader = null;
	private SharedPreferences prefs;
    private Timer readTimer;  

  class comm_thread extends Thread
  {
    JmDNS jmdns;
    withrottle_listener listener;
    MulticastLock multicast_lock;

    //Listen for a WiThrottle service advertisement on the LAN.
    public class withrottle_listener implements ServiceListener
    {
      public void serviceAdded(ServiceEvent event)
      {
        //A service has been added. Request the service's information.
        JmDNS jmdns=event.getDNS();
        jmdns.requestServiceInfo(event.getType(), event.getName(), 0);
        Log.d("serviceAdded", event.toString());
      };

      public void serviceRemoved(ServiceEvent event)
      {
        Log.d("serviceRemoved", event.getName());
      };

      public void serviceResolved(ServiceEvent event)
      {
        //A service's information has been resolved. Capture the necessary part needed to connect to that service.
        int port=event.getInfo().getPort();
        String host_ip=event.getInfo().getHostAddress();
        Log.d("serviceResolved", String.format("%s:%d", host_ip, port));
        //Tell the UI thread so as to update the list of services available.
        Message service_message=Message.obtain();
        service_message.what=message_type.SERVICE_RESOLVED;
        service_message.arg1=port;
        service_message.obj=new String(host_ip);
        ui_msg_handler.sendMessage(service_message);
      };
    }
    void end_this_thread() {
    	thread = null;
    	this.interrupt();
    }
    
    class comm_handler extends Handler
    {
      //All of the work of the communications thread is initiated from this function.
      public void handleMessage(Message msg)
      {
        switch(msg.what)
        {
          //Connect to the WiThrottle server.
          case message_type.CONNECT:
            //The IP address is stored in the obj as a String, the port is stored in arg1.
            host_ip=new String((String)msg.obj);
            host_ip = host_ip.trim();
            port=msg.arg1;
            
            //clear app.thread shared variables so they can be reset
            host_name_string = null;
            withrottle_version_string = null; 
            heartbeat_interval = 0;
            roster_list_string = null;
            try { host_address=InetAddress.getByName(host_ip); }
            catch(UnknownHostException except) {
              process_comm_error("Could not connect to " + host_ip + "\n"+except.getMessage()+"\n"+except.getCause().getMessage());
              return;
            }
            if (host_address == null) {
                process_comm_error("Could not connect to " + host_ip);
                return;            	
            }
            
            host_name_string = host_address.getHostName();  //store host server name in app.thread shared variable

            try { client_socket=new Socket();               //look for someone to answer on specified socket, and set timeout
                  InetSocketAddress sa = new InetSocketAddress(host_ip, port);
                  client_socket.connect(sa, 1500);  //TODO: adjust these timeouts
    		      client_socket.setSoTimeout(300);
            }
            catch(IOException except) {
              process_comm_error("Cannot connect to host "+host_ip+" and port "+port+": "+except.getMessage()+"\nCheck WiThrottle and network settings.");
              return;
            }

            try { output_pw=new PrintWriter(new OutputStreamWriter(client_socket.getOutputStream()), true); }
            catch(IOException except) {
              process_comm_error("Error creating a PrintWriter, IOException: "+except.getMessage());
              return;
            }

			try {input_reader = new BufferedReader(new InputStreamReader(client_socket.getInputStream()));
			} catch (IOException except) {
				process_comm_error("Error creating input stream, IOException: "+except.getMessage());
			    return;
			} 

		    String s = prefs.getString("throttle_name_preference", getApplicationContext().getResources().getString(R.string.prefThrottleNameDefaultValue));
            withrottle_send("N" + s);  //send throttle name
            withrottle_send("HU" + s);  //also send throttle name as the UDID
            Message connection_message=Message.obtain();
            connection_message.what=message_type.CONNECTED;
            ui_msg_handler.sendMessage(connection_message);
            
            start_read_timer();
            
            break;

          //Release the current loco
          case message_type.RELEASE:  //release specified loco
          	String whichThrottle = msg.obj.toString();

        	//        	Boolean f = getApplicationContext().getResources().getBoolean(R.string.prefStopOnReleaseDefaultValue); TODO: fix this
  		    if (prefs.getBoolean("stop_on_release_preference", true )) {
  		    	withrottle_send(whichThrottle+"V0");  //send stop command before releasing (if set in prefs)
  		    }
            if (whichThrottle.equals("T")) {
  		      loco_string_T = "Not Set"; 
              roster_function_string_T = null;
              loco_address_T = -1;
            } else {
              loco_string_S = "Not Set"; 
              roster_function_string_S = null;
              loco_address_S = -1;
            }
            withrottle_send(whichThrottle+"r");  //send release command
            break;

          //send heartbeat
          case message_type.HEARTBEAT:
        	withrottle_send("*");
        	//also send to engine_driver activity if active
 		    if (engine_driver_msg_handler != null) {
		       msg=Message.obtain(); 
		       msg.what=message_type.HEARTBEAT;
		       engine_driver_msg_handler.sendMessage(msg);
		    }
            break;

          //Disconnect from the WiThrottle server.
          case message_type.DISCONNECT:
        	withrottle_send("Q");
            withrottle_send("*-");     //turn off heartbeat
            readTimer.cancel();        //stop reading from socket
            try{ Thread.sleep(500); }   //  give server time to process this.
              catch (InterruptedException except){ process_comm_error("Error sleeping the thread, InterruptedException: "+except.getMessage()); }
            try { client_socket.close(); }
              catch(IOException except) { process_comm_error("Error closing the Socket, IOException: "+except.getMessage()); }
            break;

           //Set up an engine to control. The address of the engine is given in arg1, and the address type (long or short) is given in arg2.
          case message_type.LOCO_ADDR:
            //clear appropriate app-level shared variables so they can be reset
        	whichThrottle = msg.obj.toString();
            if (whichThrottle.equals("T")) {
            		loco_string_T = "Not Set"; 
                    roster_function_string_T = null;
                    loco_address_T=msg.arg1;
            } else {
        		loco_string_S = "Not Set"; 
                roster_function_string_S = null;
                loco_address_S=msg.arg1;
            }
//            withrottle_send(String.format("T"+(msg.arg2==address_type.LONG ? "L" : "S")+"%d", loco_address_T));
            withrottle_send(String.format(whichThrottle+(msg.arg2==address_type.LONG ? "L" : "S")+"%d", msg.arg1));
                     //In order to get the engine to start, I must set a direction and some non-zero velocity and then set the velocity to zero. TODO: Fix this bug
            //in the WiThrottle server.
//            withrottle_send("TR1\nTV1\nTV0");
            withrottle_send(whichThrottle+"R1\n"+whichThrottle+"V1\n"+whichThrottle+"V0"); 
            withrottle_send("*+");     //always request to turn on heartbeat (must be enabled in server prefs)
            break;

//          case message_type.ERROR:
//            break;
            
          //Adjust the locomotive's speed. arg1 holds the value of the speed to set. //TODO: Allow 14 and 28 speed steps (might need a change on the server size).
          case message_type.VELOCITY:
//          	withrottle_send(String.format("TV%d", msg.arg1));
          	whichThrottle = msg.obj.toString();
        	withrottle_send(String.format(whichThrottle+"V%d", msg.arg1));
            break;

          //Change direction. arg2 holds the direction to change to. The reason direction is in arg2 is for compatibility
          //with the function buttons.
          case message_type.DIRECTION:
          	whichThrottle = msg.obj.toString();
        	withrottle_send(String.format(whichThrottle+"R%d", msg.arg2));
            break;
          //Set or unset a function. arg1 is the function number, arg2 is set or unset.
          case message_type.FUNCTION:
          	whichThrottle = msg.obj.toString();
        	withrottle_send(String.format(whichThrottle+"F%d%d", msg.arg2, msg.arg1));
            break;
/*
          //end the application and thread
          case message_type.SHUTDOWN:
        	//forward end message to all active activities
            Message fwd_msg=Message.obtain();
            fwd_msg.what=message_type.END_ACTIVITY;
            if (engine_driver_msg_handler != null) { engine_driver_msg_handler.sendMessage(fwd_msg); }
            if (select_loco_msg_handler != null) { select_loco_msg_handler.sendMessage(fwd_msg); }
            if (ui_msg_handler != null) { ui_msg_handler.sendMessage(fwd_msg); }
            end_this_thread(); 
            break;
*/
        }
      };
    }
    private void process_comm_error(String msg_txt) {
        Log.e("comm_handler.handleMessage", msg_txt);
        Message ui_msg=Message.obtain();
        ui_msg.what=message_type.ERROR;
        ui_msg.obj = new String(msg_txt);  //put error message text in message
        if (ui_msg_handler != null) {
          ui_msg_handler.sendMessage(ui_msg); //send message to ui thread for display
        }
    }

    private void process_response(String response_str) {
    /* see java/arc/jmri/jmrit/withrottle/deviceserver.java for server code and some documentation
    	  VN<Version#>
    	  T<EngineAddress>(<LongOrShort>)  
    	  S<2ndEngineAddress>(<LongOrShort>)
    	  RL<RosterSize>]<RosterList>
    	  RF<RosterFunctionList>
    	  RS<2ndRosterFunctionList>
    	  *<HeartbeatIntervalInSeconds>      */

    	//send response to debug log for review
        Log.d("Engine_Driver", "<--:" + response_str);

        switch (response_str.charAt(0)) {
	  	case 'T': 
	  		loco_string_T = response_str.substring(1);  //set app variable
 	  	    break;
	  	
	  	case 'S': 
	  		loco_string_S = response_str.substring(1);  //set app variable
	  	    break;
	  	
	  	case 'V': 
	  		withrottle_version_string = response_str.substring(2);  //set app variable
	  	    break;
	  	
	  	case '*': 
	  		heartbeat_interval = Integer.parseInt(response_str.substring(1));  //set app variable
	  	    break;
	  	
	  	case 'R': //Roster
    	  switch (response_str.charAt(1)) {
    	  	case 'L': 
    	  		roster_list_string = response_str.substring(2);  //set app variable
    	  	    break;
    	  	
    	  	case 'F': 
    	  		roster_function_string_T = response_str.substring(2);  //set app variable for throttle 1
    	  	    break;
        	  	
    	  	case 'S': 
    	  		roster_function_string_S = response_str.substring(2);  //set app variable for throttle 2
    	  	    break;
        	  	
    	  	case 'P': //Properties
    	  		if 	(response_str.charAt(2) == 'F') {  //function state 
    	  			process_function_state(response_str);  //process function state message (passing the whole message)
    	  		}
    	  	    break;
    	  }  //end switch inside R
	  	 break;
  	  }  //end switch
  	  
  	  //forward whatever we got to other activities (if started)
      Message msg=Message.obtain(); 
      msg.what=message_type.RESPONSE;
      msg.obj=new String(response_str); 
      if (engine_driver_msg_handler != null) { engine_driver_msg_handler.sendMessage(msg); }
//      if (select_loco_msg_handler != null)   { select_loco_msg_handler.sendMessage(msg);   }
      

    }  //end of process_response

    //parse function state string into appropriate app variable array
    private void process_function_state(String response_str) {
    
     String whichThrottle = null;
     
     String[] sa = splitByString(response_str,"]\\[F");  //initial separation (note that I include the F just to strip it off and simplify later stuff
     int i = 0;
     for (String fs : sa) {
    	 String[] fa = splitByString(fs,"}|{");  //split these into 2 parts, key and value
    	 if (i == 0) { //first chunk is different, contains whichThrottle
    	    whichThrottle = fa[1];
    	 } else {  //all others have function#, then value
    		 int fn = Integer.parseInt(fa[0]);
    		 boolean fState = Boolean.parseBoolean(fa[1]);
    		 
    		 if (whichThrottle.equals("T")) {
    			 function_states_T[fn] = fState;
    		 }  else {
    			 function_states_S[fn] = fState;
    		 }
    	 }  //end if i==0
    	 i++;
     }  //end for
     
  }
  
    //send the passed-in message to the socket
    private void withrottle_send(String msg) {
    	if (output_pw != null) {
      	  output_pw.println(msg);
      	  output_pw.flush();
      	} else {
          process_comm_error("No writer, tried to send: "+msg);
      	}
        //send response to debug log for review
        Log.d("Engine_Driver", "-->:" + msg);
    }  //end withrottle_send()


    //setup a loop to read from the socket 
    void start_read_timer() {
      readTimer = new Timer();
	  readTimer.schedule(new TimerTask() {
		@Override
		public void run() {
			withrottle_rcv();
		}
	}, 1000, 2000 );  //1 sec on first fire, 2 sec on subsequent ones
}

    
    //read anything coming back from server and call process_response for it.
    private void withrottle_rcv() {
      	  
     	  //read responses from withrottle and send non-blank ones to other activities
          readTimer.cancel();  //don't double-fire
      	  String str = null;
			try {
				while ((str = input_reader.readLine()) != null) {  //loop until no more data found
					if (str.length()>0) {
						process_response(str);
					}
				}
			} catch  (SocketTimeoutException e )   {
				start_read_timer();  //delay and read again
			} catch (IOException e) {
  	            readTimer.cancel();  //stop trying to read if an error occurred
//				e.printStackTrace();
  	            process_comm_error("withrottle_rcv err:" + e.getMessage());  
			} 
      	  
    }  //end withrottle_rcv()

    public void run()
    {
      int intaddr = 0;
      //Set up to find a WiThrottle service via ZeroConf.
      
      try
      {
        WifiManager wifi = (WifiManager)threaded_application.this.getSystemService(Context.WIFI_SERVICE);
        //Acquire a multicast lock. This allows us to obtain multicast packets, but consumes a bit more battery life.
        //Release it as soon as possible (after the user has connected to a WiThrottle service, or this application is
        //not the currently active one.
        multicast_lock=wifi.createMulticastLock("engine_driver");
        multicast_lock.setReferenceCounted(true);
        multicast_lock.acquire();
        WifiInfo wifiinfo = wifi.getConnectionInfo();
        intaddr = wifiinfo.getIpAddress();
        if (intaddr != 0) {
          byte[] byteaddr = new byte[] { (byte)(intaddr & 0xff), (byte)(intaddr >> 8 & 0xff), (byte)(intaddr >> 16 & 0xff),
                                       (byte)(intaddr >> 24 & 0xff) };
          InetAddress addr = InetAddress.getByAddress(byteaddr);
          String s = String.format("found intaddr=%d, addr=%s", intaddr, addr.toString());
          Log.d("comm_thread_run", s);

          jmdns=JmDNS.create(addr);
          listener=new withrottle_listener();
          jmdns.addServiceListener("_withrottle._tcp.local.", listener);
        } else {
          process_comm_error("No IP Address found.\nCheck your WiFi connection.");
        }  //end of if intaddr==0
      }
      catch(IOException except) { 
    	  Log.e("comm_thread_run", "Error creating withrottle listener: IOException: "+except.getMessage()); 
          process_comm_error("Error creating withrottle listener: IOException: \n"+except.getMessage()+"\n"+except.getCause().getMessage()); 
      }

      Looper.prepare();
      comm_msg_handler=new comm_handler();
      Looper.loop();
    };
  }
  public void onCreate()
  {
	prefs = getSharedPreferences("jmri.enginedriver_preferences", 0);
	function_states_T = new boolean[32];
	function_states_S = new boolean[32];
	
    thread=new comm_thread();
    thread.start();
  }

/** ------ copied from jmri util code -------------------
 * Split a string into an array of Strings, at a particular
 * divider.  This is similar to the new String.split method,
 * except that this does not provide regular expression
 * handling; the divider string is just a string.
 * @param input String to split
 * @param divider Where to divide the input; this does not appear in output
 */
  static public String[] splitByString(String input, String divider) {
    int size = 0;
    String temp = input;
    
    // count entries
    while (temp.length() > 0) {
        size++;
        int index = temp.indexOf(divider);
        if (index < 0) break;    // break not found
        temp = temp.substring(index+divider.length());
        if (temp.length() == 0) {  // found at end
            size++;
            break;
        }
    }
    
    String[] result = new String[size];
    
    // find entries
    temp = input;
    size = 0;
    while (temp.length() > 0) {
        int index = temp.indexOf(divider);
        if (index < 0) break;    // done with all but last
        result[size] = temp.substring(0,index);
        temp = temp.substring(index+divider.length());
        size++;
    }
    result[size] = temp;
    
    return result;
  }
}
