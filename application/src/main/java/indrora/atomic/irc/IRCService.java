/*
Yaaic - Yet Another Android IRC Client

Copyright 2009-2013 Sebastian Kaspari
Copyright 2012 Daniel E. Moctezuma <democtezuma@gmail.com>

This file is part of Yaaic.

Yaaic is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Yaaic is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Yaaic.  If not, see <http://www.gnu.org/licenses/>.
 */
package indrora.atomic.irc;

import indrora.atomic.Atomic;
import indrora.atomic.R;
import indrora.atomic.activity.ConversationActivity;
import indrora.atomic.activity.ServersActivity;
import indrora.atomic.db.Database;
import indrora.atomic.model.Broadcast;
import indrora.atomic.model.Conversation;
import indrora.atomic.model.Message;
import indrora.atomic.model.Server;
import indrora.atomic.model.ServerInfo;
import indrora.atomic.model.Settings;
import indrora.atomic.model.Status;
import indrora.atomic.model.Message.MessageColor;
import indrora.atomic.utils.MircColors;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509TrustManager;

import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.NickAlreadyInUseException;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import de.duenndns.ssl.MemorizingTrustManager;

/**
 * The background service for managing the irc connections
 *
 * @author Sebastian Kaspari <sebastian@yaaic.org>
 */
public class IRCService extends Service {
  public static final String ACTION_FOREGROUND = "indrora.atomic.service.foreground";
  public static final String ACTION_BACKGROUND = "indrora.atomic.service.background";
  public static final String ACTION_ACK_NEW_MENTIONS = "indrora.atomic.service.ack_new_mentions";
  public static final String EXTRA_ACK_SERVERID = "indrora.atomic.service.ack_serverid";
  public static final String EXTRA_ACK_CONVTITLE = "indrora.atomic.service.ack_convtitle";

  private static final int FOREGROUND_NOTIFICATION = 1;
  private static final int NOTIFICATION_LED_OFF_MS = 1000;
  private static final int NOTIFICATION_LED_ON_MS = 300;
  private static final int NOTIFICATION_LED_COLOR = 0xff00ff00;

  @SuppressWarnings("rawtypes")
  private static final Class[] mStartForegroundSignature = new Class[]{int.class, Notification.class};
  @SuppressWarnings("rawtypes")
  private static final Class[] mStopForegroundSignature = new Class[]{boolean.class};
  @SuppressWarnings("rawtypes")
  private static final Class[] mSetForegroudSignaure = new Class[]{boolean.class};

  private final IRCBinder binder;
  private final HashMap<Integer, IRCConnection> connections;
  private boolean foreground = false;
  private final ArrayList<String> connectedServerTitles;
  private final LinkedHashMap<String, Conversation> mentions;
  private int newMentions = 0;

  private NotificationManager notificationManager;
  private Method mStartForeground;
  private Method mStopForeground;
  private final Object[] mStartForegroundArgs = new Object[2];
  private final Object[] mStopForegroundArgs = new Object[1];
  //private Notification notification;
  private Settings settings;

  /**
   * *
   * <p/>
   * This class will handle the network changes.
   */
  private class NetworkTransitionHandler extends BroadcastReceiver {
    // Sets up the Transition handler -- the context here is so that the connectivity manager can be found.
    private NetworkTransitionHandler(Context ctx) {
      NetworkInfo networkInfo = ((ConnectivityManager)(ctx.getSystemService(Service.CONNECTIVITY_SERVICE))).getActiveNetworkInfo();
      if( networkInfo == null )
        lastNetworkType = -1;
      else
        lastNetworkType = networkInfo.getType();
    }

    // previous network kind we were on (-1 => We do not know yet)
    private int lastNetworkType = -1;

    private static final String TAG = "NetworkTransitions";

    // This is called when we get an intent for connectivty
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if( !action.equals(ConnectivityManager.CONNECTIVITY_ACTION) ) {
        return;
      }
      // _isTransient means "network is in transition -> we should know this.
      IRCService.this._isTransient = true;

      NetworkInfo networkInfo = ((ConnectivityManager)(context.getSystemService(Service.CONNECTIVITY_SERVICE))).getActiveNetworkInfo();
      int newNetworkType = -1;
      if( networkInfo == null ) {
        Log.d(TAG, "Lost all connectivity.");
        lastNetworkType = -1;
        // There's a bug here when no network is availible at all -- I'm not certain what is really going on.
        _isTransient = false;
        return;
      } else if( networkInfo.isConnected() ) {
        Log.d(TAG, "new network type: " + networkInfo.getTypeName());
        newNetworkType = networkInfo.getType();
      }

      if( newNetworkType != lastNetworkType ) {
        Log.d(TAG, "Transition from network " + lastNetworkType + " to " + newNetworkType);
        IRCService.this.networksChanged(newNetworkType);
        lastNetworkType = newNetworkType;
      }

    }

  }

  private static NetworkTransitionHandler _netTransitionHandler;
  private static ArrayList<Integer> reconnectNextNetwork;

  /**
   * Create new service
   */
  public IRCService() {
    super();

    this.connections = new HashMap<Integer, IRCConnection>();
    this.binder = new IRCBinder(this);
    this.connectedServerTitles = new ArrayList<String>();
    this.mentions = new LinkedHashMap<String, Conversation>();

    reconnectNextNetwork = new ArrayList<Integer>();
  }

  private boolean _isTransient = false;

  public boolean isNetworkTransient() {
    Log.d("IRCService", "Network is transient: " + _isTransient);
    return _isTransient;
  }

  protected synchronized void networksChanged(int newNetworkType) {
    // If new network is -1, we need to configure reconnecting.
    _isTransient = true;
    if( newNetworkType == -1 && settings.reconnectLoss() ) {
      updateNotification(getString(R.string.notification_not_connected), "Waiting for network", Notification.PRIORITY_LOW, false, false, false);
      reconnectNextNetwork.clear();
      for( int sid : connections.keySet() ) {
        reconnectNextNetwork.add(sid);

        Intent sIntent = Broadcast.createServerIntent(Broadcast.SERVER_UPDATE, sid);
        sendBroadcast(sIntent);
      }

    } else {
      // We're changing between networks, not losing our network entirely.
      if( settings.reconnectTransient() ) {
        updateNotification(getString(R.string.notification_not_connected), "Network in transition", Notification.PRIORITY_LOW, false, false, false);
        for( int sid : connections.keySet() ) {
          connections.get(sid).disconnect(); // Disconnect and clean up.
          if( !reconnectNextNetwork.contains(sid) )
            reconnectNextNetwork.add(sid);
        }
      }

      final Integer[] new_servers = (Integer[])reconnectNextNetwork.toArray(new Integer[reconnectNextNetwork.size()]);
      for( int reconnect_server : new_servers ) {

        Server s = Atomic.getInstance().getServerById(reconnect_server);

        this.getConnection(reconnect_server).disconnect();

        s.setStatus(Status.PRE_CONNECTING);
        Intent sIntent = Broadcast.createServerIntent(Broadcast.SERVER_UPDATE, reconnect_server);

        sendBroadcast(sIntent);


        Message message = new Message("Waiting on network connectivity");
        message.setIcon(R.drawable.info);
        message.setColor(MessageColor.SERVER_EVENT);

        s.getConversation(ServerInfo.DEFAULT_NAME).addMessage(message);

        Intent cIntent = Broadcast.createConversationIntent(
            Broadcast.CONVERSATION_MESSAGE,
            reconnect_server,
            ServerInfo.DEFAULT_NAME
        );
        sendBroadcast(cIntent);


        connect(s);


        reconnectNextNetwork.remove((Object)reconnect_server);
      }

      reconnectNextNetwork.clear();

    }
    checkServiceStatus();

  }

  public synchronized void removeReconnection(int sid) {
    if( !reconnectNextNetwork.contains(sid) ) {
      return; // This server doesn't currently have a reconnect listing.
    }
    reconnectNextNetwork.remove(reconnectNextNetwork.indexOf(sid));
  }

  public synchronized void clearReconnectList() {
    reconnectNextNetwork.clear();
  }

  public synchronized boolean isReconnecting(int sid) {
    return reconnectNextNetwork.contains(sid);
  }

  /**
   * On create
   */
  @Override
  public void onCreate() {
    super.onCreate();
    _netTransitionHandler = new NetworkTransitionHandler(this);
    settings = new Settings(getBaseContext());
    notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

    try {
      mStartForeground = getClass().getMethod("startForeground", mStartForegroundSignature);
      mStopForeground = getClass().getMethod("stopForeground", mStopForegroundSignature);
    } catch ( NoSuchMethodException e ) {
      // Running on an older platform.
      mStartForeground = mStopForeground = null;
    }


    // Load servers from Database
    Database db = new Database(this);
    Atomic.getInstance().setServers(db.getServers());
    db.close();

    // Broadcast changed server list
    sendBroadcast(new Intent(Broadcast.SERVER_UPDATE));


    // Set up our connectivity handler
    registerReceiver(_netTransitionHandler, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));


  }

  /**
   * Get Settings object
   *
   * @return the settings helper object
   */
  public Settings getSettings() {
    return settings;
  }

  /**
   * On start (will be called on pre-2.0 platform. On 2.0 or later onStartCommand()
   * will be called)
   */
  @Override
  public void onStart(Intent intent, int startId) {
    super.onStart(intent, startId);
    handleCommand(intent);
  }

  /**
   * On start command (Android >= 2.0)
   *
   * @param intent
   * @param flags
   * @param startId
   * @return
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if( intent != null ) {
      handleCommand(intent);
    }

    // We want this service to continue running until it is explicitly
    // stopped, so return sticky.
    //return START_STICKY;
    return 1;
  }


  /**
   * Handle command
   *
   * @param intent
   */
  private void handleCommand(Intent intent) {
    if( ACTION_FOREGROUND.equals(intent.getAction()) ) {
      if( foreground ) {
        return; // XXX: We are already in foreground...
      }
      foreground = true;


      Intent notifyIntent = new Intent(this, ServersActivity.class);
      notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notifyIntent, 0);


      // Set the icon, scrolling text and timestamp
      // now using NotificationCompat for Linter happiness
      Notification notification = new NotificationCompat.Builder(this)
          .setSmallIcon(R.drawable.ic_service_icon)
          .setWhen(System.currentTimeMillis())
          .setContentText(getText(R.string.notification_running))
          .setTicker(getText(R.string.notification_not_connected))
          .setContentTitle(getText(R.string.app_name))
          .setContentIntent(contentIntent)
          .setPriority(Notification.PRIORITY_MIN)
          .build();

      startForegroundCompat(FOREGROUND_NOTIFICATION, notification);
    } else if( ACTION_BACKGROUND.equals(intent.getAction()) && !foreground ) {
      stopForegroundCompat(FOREGROUND_NOTIFICATION);
    } else if( ACTION_ACK_NEW_MENTIONS.equals(intent.getAction()) ) {
      ackNewMentions(intent.getIntExtra(EXTRA_ACK_SERVERID, -1), intent.getStringExtra(EXTRA_ACK_CONVTITLE));
    }
  }

  /**
   * Update notification and vibrate and/or flash a LED light if needed
   *
   * @param text        The ticker text to display
   * @param contentText The text to display in the notification dropdown
   *                    If null, this makes the notification update to be the connection status.
   * @param vibrate     True if the device should vibrate, false otherwise
   * @param sound       True if the device should make sound, false otherwise
   * @param light       True if the device should flash a LED light, false otherwise
   */
  private void updateNotification(String text, String contentText, int priority, boolean vibrate, boolean sound, boolean light) {
    if( foreground ) {

      // NotificationCompat does the right things for ICS
      // and other various variants. Seriously, Google?


      NotificationCompat.Builder notificationB = new NotificationCompat.Builder(this)
          .setSmallIcon(R.drawable.ic_service_icon)
          .setWhen(System.currentTimeMillis())
          .setPriority(priority);

      Intent notifyIntent = new Intent(this, ServersActivity.class);

      // If contentText is null, there's nothing to show
      // However, if it isn't null, display that.
      if( contentText == null ) {
        if( newMentions >= 1 ) {
          StringBuilder sb = new StringBuilder();
          for( Conversation conv : mentions.values() ) {
            sb.append(conv.getName() + " (" + conv.getNewMentions() + "), ");
          }
          contentText = getString(R.string.notification_mentions,
              sb.substring(0, sb.length() - 2));

          // We're going to work through the mentions keys. The first half is
          // the server ID, the other half
          // is the channel that the mention belongs to.

          int ServerID = -1;
          String Convo = "";

          String convID = ((String)(mentions.keySet().toArray()[mentions.size() - 1]));
          ServerID = Integer
              .parseInt(convID.substring(0, convID.indexOf(':')));
          Convo = convID.substring(convID.indexOf(':') + 1);


          Log.d("IRCService", "Jump target is '" + Convo + "'");
          notifyIntent.setClass(this, ConversationActivity.class);
          notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
              | Intent.FLAG_ACTIVITY_CLEAR_TOP);
          notifyIntent.putExtra("serverId", ServerID);
          notifyIntent.putExtra(ConversationActivity.EXTRA_TARGET, "" + Convo);

        } else {
          if( !connectedServerTitles.isEmpty() ) {
            StringBuilder sb = new StringBuilder();
            notificationB.setPriority(Notification.PRIORITY_MIN);

            for( String title : connectedServerTitles ) {
              sb.append(title + ", ");
            }
            contentText = getString(R.string.notification_connected,
                sb.substring(0, sb.length() - 2));
          } else {
            contentText = getString(R.string.notification_not_connected);

          }
        }
      }

      PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT);

      notificationB.setContentIntent(contentIntent);
      notificationB.setContentTitle(getText(R.string.app_name));
      notificationB.setOngoing(false);
      notificationB.setNumber(newMentions);


      NotificationCompat.Builder publicBuilder = new NotificationCompat.Builder(this);
      publicBuilder.setSmallIcon(R.drawable.ic_service_icon);
      publicBuilder.setContentTitle(getText(R.string.app_name));


      if(newMentions > 0) {
        publicBuilder.setContentText(getString(R.string.notification_mentions, newMentions));
        publicBuilder.setNumber(newMentions);

      }
      notificationB.setPublicVersion(publicBuilder.build());
      notificationB.setContentText(contentText);
      if( text != null ) {
        notificationB.setTicker(text);
      }
      if(light) {
        notificationB.setLights(settings.getHighlightLEDColor(), NOTIFICATION_LED_ON_MS, NOTIFICATION_LED_OFF_MS);
      }
      if(sound) {
        notificationB.setSound(settings.getHighlightSoundLocation());
      }
      if(vibrate) {
        notificationB.setVibrate(new long[] {0, 200, 180, 160, 140, 120, 100});
      }


      Notification notification = notificationB.build();
      notificationManager.notify(FOREGROUND_NOTIFICATION, notification);
    }
  }

  /**
   * Generates a string uniquely identifying a conversation.
   */
  public String getConversationId(int serverId, String title) {
    return "" + serverId + ":" + title;
  }

  /**
   * Notify the service of a new mention (updates the status bar notification)
   *
   * @param conversation The conversation where the new mention occurred
   * @param msg          The text of the new message
   * @param vibrate      Whether the notification should include vibration
   * @param sound        Whether the notification should include sound
   * @param light        Whether the notification should include a flashing LED light
   */
  public synchronized void addNewMention(int serverId, Conversation conversation, String msg, boolean vibrate, boolean sound, boolean light) {
    if( conversation == null ) {
      return;
    }

    conversation.addNewMention();
    ++newMentions;
    String convId = getConversationId(serverId, conversation.getName());
    if( !mentions.containsKey(convId) ) {
      mentions.put(convId, conversation);
    }
    msg = MircColors.removeStyleAndColors(msg);
    updateNotification(msg, null, Notification.PRIORITY_MAX, vibrate, sound, light);
  }

  /**
   * Notify the service that new mentions have been viewed (updates the status bar notification)
   *
   * @param convTitle The title of the conversation whose new mentions have been read
   */
  public synchronized void ackNewMentions(int serverId, String convTitle) {
    if( convTitle == null ) {
      return;
    }

    Conversation conversation = mentions.remove(getConversationId(serverId, convTitle));
    if( conversation == null ) {
      return;
    }
    newMentions -= conversation.getNewMentions();
    conversation.clearNewMentions();
    if( newMentions < 0 ) {
      newMentions = 0;
    }

    updateNotification(null, null, Notification.PRIORITY_MIN, false, false, false);
  }

  /**
   * Notify the service of connection to a server (updates the status bar notification)
   *
   * @param title The title of the newly connected server
   */
  public synchronized void notifyConnected(String title) {
    connectedServerTitles.add(title);
    updateNotification(getString(R.string.notification_connected, title),  null, Notification.PRIORITY_HIGH, false, false, false);
  }

  /**
   * Notify the service of disconnection from a server (updates the status bar notification)
   *
   * @param title The title of the disconnected server
   */
  public synchronized void notifyDisconnected(String title) {
    connectedServerTitles.remove(title);
    updateNotification(getString(R.string.notification_disconnected, title),  null,Notification.PRIORITY_DEFAULT, false, false, false);
  }


  /**
   * This is a wrapper around the new startForeground method, using the older
   * APIs if it is not available.
   */
  private void startForegroundCompat(int id, Notification notification) {
    // If we have the new startForeground API, then use it.
    if( mStartForeground != null ) {
      mStartForegroundArgs[0] = Integer.valueOf(id);
      mStartForegroundArgs[1] = notification;
      try {
        mStartForeground.invoke(this, mStartForegroundArgs);
      } catch ( InvocationTargetException e ) {
        // Should not happen.
      } catch ( IllegalAccessException e ) {
        // Should not happen.
      }
    } else {
      // Fall back on the old API.
      try {
        Method setForeground = getClass().getMethod("setForeground", mSetForegroudSignaure);
        setForeground.invoke(this, new Object[]{true});
      } catch ( NoSuchMethodException exception ) {
        // Should not happen
      } catch ( InvocationTargetException e ) {
        // Should not happen.
      } catch ( IllegalAccessException e ) {
        // Should not happen.
      }

      notificationManager.notify(id, notification);
    }
  }

  /**
   * This is a wrapper around the new stopForeground method, using the older
   * APIs if it is not available.
   */
  public void stopForegroundCompat(int id) {
    foreground = false;

    // If we have the new stopForeground API, then use it.
    if( mStopForeground != null ) {
      mStopForegroundArgs[0] = Boolean.TRUE;
      try {
        mStopForeground.invoke(this, mStopForegroundArgs);
      } catch ( InvocationTargetException e ) {
        // Should not happen.
      } catch ( IllegalAccessException e ) {
        // Should not happen.
      }
    } else {
      // Fall back on the old API.  Note to cancel BEFORE changing the
      // foreground state, since we could be killed at that point.
      notificationManager.cancel(id);

      try {
        Method setForeground = getClass().getMethod("setForeground", mSetForegroudSignaure);
        setForeground.invoke(this, new Object[]{true});
      } catch ( NoSuchMethodException exception ) {
        // Should not happen
      } catch ( InvocationTargetException e ) {
        // Should not happen.
      } catch ( IllegalAccessException e ) {
        // Should not happen.
      }
    }
  }

  /**
   * Connect to the given server
   */
  public void connect(final Server server) {
    final int serverId = server.getId();
    final IRCService service = this;

    if( settings.isReconnectEnabled() ) {
      server.setMayReconnect(true);
    }

    new Thread("Connect thread for " + server.getTitle()) {
      @Override
      public void run() {

        if( settings.isReconnectEnabled() && !server.mayReconnect() ) {
          return;
        }

        try {
          I_AM_A_HORRIBLE_BASTARD:
          ;
          IRCConnection connection = getConnection(serverId);

          connection.setNickname(server.getIdentity().getNickname());
          connection.setAliases(server.getIdentity().getAliases());
          connection.setIdent(server.getIdentity().getIdent());
          connection.setRealName(server.getIdentity().getRealName());
          connection.setUseSSL(server.useSSL());

          // trust manager
          MemorizingTrustManager mtm = new MemorizingTrustManager(getApplicationContext());
          HostnameVerifier hv = mtm.wrapHostnameVerifier(new org.apache.http.conn.ssl.StrictHostnameVerifier());

          X509TrustManager[] trustMgr = MemorizingTrustManager.getInstanceList(getApplicationContext());
          connection.setupTrustManagers(trustMgr, hv);

          if( server.getCharset() != null ) {
            connection.setEncoding(server.getCharset());
          }

          if( server.getAuthentication().hasSaslCredentials() ) {
            connection.setSaslCredentials(
                server.getAuthentication().getSaslUsername(),
                server.getAuthentication().getSaslPassword()
            );
          }

          if( server.getPassword() != "" ) {
            connection.connect(server.getHost(), server.getPort(), server.getPassword());
          } else {
            connection.connect(server.getHost(), server.getPort());
          }
        } catch ( Exception e ) {
          server.setStatus(Status.DISCONNECTED);

          NetworkInfo ninf = ((ConnectivityManager)(IRCService.this.getSystemService(Service.CONNECTIVITY_SERVICE))).getActiveNetworkInfo();
          if( ninf == null ) {
            _isTransient = false;
          } else {
            _isTransient = !(ninf.getState() == NetworkInfo.State.CONNECTED);
          }

          Intent sIntent = Broadcast.createServerIntent(Broadcast.SERVER_UPDATE, serverId);
          sendBroadcast(sIntent);

          final IRCConnection connection = getConnection(serverId);

          Message message;

          if( e instanceof NickAlreadyInUseException ) {
            message = new Message(getString(R.string.nickname_in_use, connection.getNick()));
            server.setMayReconnect(false);
          } else if( e instanceof IrcException ) {
            message = new Message(getString(R.string.irc_login_error, server.getHost(), server.getPort()));
            server.setMayReconnect(false);
          } else if( e instanceof SSLException ) {
            // This happens when we declined the SSL certificate most of the time
            // We should check what really happened.
            message = new Message("SSL negotiation failed: " + e.toString());
          } else {
            message = new Message(getString(R.string.could_not_connect, server.getHost(), server.getPort()) + ":\n" + e.toString());

            if( settings.isReconnectEnabled() ) {
              updateNotification("Reconnecting.", "Reconnection queued", Notification.PRIORITY_MIN, false, false, false);
              server.setStatus(Status.RECONNECTING);
              IRCService.this.sendBroadcast(Broadcast.createServerIntent(
                  Broadcast.SERVER_UPDATE, serverId));

              if( _isTransient && (settings.reconnectTransient() || settings.reconnectLoss()) ) {
                reconnectNextNetwork.add(serverId);
                message = new Message(
                    "Connection will be established once network is connected");
              } else {
                message = new Message("Reconnecting to server... ");
                Runnable r = new Runnable() {

                  @Override
                  public void run() {
                    Log.d("IRCService", "In reconnect thread!");
                    connection.disconnect();
                    try {
                      Thread.sleep(5000);
                    } catch ( Exception eee ) {
                      ;
                      ;
                    }
                    if( server.getStatus() != Status.DISCONNECTED ) {
                      connect(server);
                    }
                  }
                };
                (new Thread(r)).run();
              }
            }
          }

          message.setColor(Message.MessageColor.ERROR);
          message.setIcon(R.drawable.error);
          server.getConversation(ServerInfo.DEFAULT_NAME).addMessage(message);

          Intent cIntent = Broadcast.createConversationIntent(
              Broadcast.CONVERSATION_MESSAGE,
              serverId,
              ServerInfo.DEFAULT_NAME
          );
          sendBroadcast(cIntent);
        }
      }
    }.start();
  }

  private void runRunnable(Runnable r) {
    (new Handler()).post(r);
  }

  /**
   * Get connection for given server
   *
   * @param serverId
   * @return
   */
  public synchronized IRCConnection getConnection(int serverId) {
    IRCConnection connection = connections.get(serverId);

    if( connection == null ) {
      connection = new IRCConnection(this, serverId);
      connections.put(serverId, connection);
    }

    return connection;
  }

  /**
   * Does the service keep a connection object for this server?
   *
   * @return true if there's a connection object, false otherwise
   */
  public boolean hasConnection(int serverId) {
    return connections.containsKey(serverId);
  }

  /**
   * Check status of service
   */
  public void checkServiceStatus() {
    boolean shutDown = true;
    ArrayList<Server> mServers = Atomic.getInstance().getServersAsArrayList();
    int mSize = mServers.size();
    Server server;

    for( int i = 0; i < mSize; i++ ) {
      server = mServers.get(i);
      if( server.isDisconnected() && !server.mayReconnect() ) {
        int serverId = server.getId();
        synchronized (this) {
          IRCConnection connection = connections.get(serverId);
          if( connection != null ) {
            connection.dispose();
          }
          connections.remove(serverId);
        }
      } else {
        shutDown = false;
      }
    }

    if( shutDown ) {
      foreground = false;
      stopForegroundCompat(R.string.app_name);
      stopSelf();
    }
  }

  /**
   * On Destroy
   */
  @Override
  public void onDestroy() {
    // Make sure our notification is gone.
    if( foreground ) {
      stopForegroundCompat(R.string.app_name);
    }

    unregisterReceiver(_netTransitionHandler);
  }

  /**
   * On Activity binding to this service
   *
   * @param intent
   * @return
   */
  @Override
  public IRCBinder onBind(Intent intent) {
    return binder;
  }
}
