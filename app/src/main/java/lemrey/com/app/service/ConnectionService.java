package lemrey.com.app.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import lemrey.com.app.DeviceRegister;
import lemrey.com.app.device.Device;
import lemrey.com.app.device.Feature;
import lemrey.com.app.rule.Rule;
import lemrey.com.app.rule.RuleBook;

public class ConnectionService extends Service {

	private static final String TAG = "ConnectionService";
	private final static Set<ConnectionThread> mConnections = new HashSet<>();
	private static boolean mIsRunning = false;
	private final MessageHandler mHandler;
	private final HandlerThread mHandlerThread;

	public ConnectionService() {
		mHandlerThread = new HandlerThread("events");
		mHandlerThread.start();
		mHandler = new MessageHandler(mHandlerThread.getLooper());
	}

	public static boolean isRunning() {
		return mIsRunning;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		//Log.d(TAG, "onStartCommand");
		mIsRunning = true;
		startConnections();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mIsRunning = false;
		// Shutdown threads
		for (ConnectionThread thread : mConnections) {
			if (!thread.isInterrupted()) thread.interrupt();
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}


	/**
	 * Starts connections to all known devices
	 */
	private void startConnections() {
		Log.d(TAG, "Starting connections!");
		for (Device device : DeviceRegister.devices()) {
			if (!device.isConnected()) {
				device.setConnecting();
				startConnection(device.mAddress);
			}
		}
	}

	/**
	 * Starts a connection to a device.
	 * A new thread is created for the connection and its handler added in mConnections.
	 *
	 * @param deviceAddress the device address
	 */
	private void startConnection(String deviceAddress) {
		if (!mConnections.contains(deviceAddress)) {
			final ConnectionThread thread = new ConnectionThread(deviceAddress, mHandler);
			mConnections.add(thread);
			thread.start();
		}
	}

	/**
	 * Removes the Thread for a given device
	 */
	private void stopThread(String deviceAddr) {
		Log.d(TAG, "Stopping thread " + deviceAddr + " " + mConnections.size());
		final Iterator<ConnectionThread> threadIterator = mConnections.iterator();
		while (threadIterator.hasNext()) {
			ConnectionThread thread = threadIterator.next();
			//noinspection EqualsBetweenInconvertibleTypes
			if (thread.equals(deviceAddr) && thread.isAlive()) {
				threadIterator.remove();
			}
		}
	}

	// TODO: refactor as Rule, Param
	private void sendCommand(String deviceAddr, String cmdName, String param) {
		JSONObject jsonData = new JSONObject();
		JSONObject jsonBody = new JSONObject();
		try {
			jsonBody.putOpt("name", cmdName);
			if (!param.isEmpty()) {
				for (Feature ft : DeviceRegister.device(deviceAddr).commands()) {
					if (ft.name.equals(cmdName)) {
						switch (ft.paramType) {
							case NUMBER:
								jsonBody.put("param", (int) Integer.valueOf(param));
								break;
							case TEXT:
								jsonBody.put("param", param);
								break;
						}
					}
				}
			}
			jsonData.put("msg", "cmd");
			jsonData.put("command", jsonBody);
		} catch (JSONException e) {
			e.printStackTrace();
		}

		sendData(deviceAddr, jsonData.toString());
	}

	private void sendData(String deviceAddr, String data) {
		for (ConnectionThread thread : mConnections) {
			if (thread.mAddress.equals(deviceAddr)) {
				thread.write(data);
			}
		}
	}

	private void processPong(String address) {

	}

	private void processEvent(String address, JSONObject jsonEvent) {
		final String eventName = jsonEvent.optString("name");
		final String param = jsonEvent.optString("param");
		Log.d(TAG, "Received event " + eventName + "from " + address + " param " + param);
		Log.d(TAG, jsonEvent.toString());
		final Rule rule = RuleBook.match(address, eventName);
		if (rule != null) {
			Log.d(TAG, "rule match! sending cmd");
			// TODO: should be Rule, Param
			sendCommand(rule.destAddr, rule.cmd.name, param);
		}
	}

	private void parseData(Device device, String data) {
		try {
			JSONObject jsonData = new JSONObject(data);
			MessageType msg = MessageType.fromData(jsonData.getString("msg"));
			switch (msg) {
				case PONG: {
					Log.d(TAG, "Parsing PONG: " + data);
					String name = jsonData.getString("name");
					device.setSmartName(name);
					List<Feature> events = Feature.parseEvents(jsonData.getJSONArray("events"));
					List<Feature> cmds = Feature.parseCommands(jsonData.getJSONArray("commands"));
					device.addFeatures(events, cmds);
					//mConnectionCallback.onPayloadReceived(device.mAddress);
					final Intent intent = new Intent("bing");
					sendBroadcast(intent);
				}
				break;
				case EVENT: {
					Log.d(TAG, "Parsing EVENT");
					//Event event = new Event(jsonData.getJSONObject("event").getString("name"));
					processEvent(device.mAddress, jsonData.getJSONObject("event"));
				}
				break;
			}
		} catch (JSONException e) {
			Log.d(TAG, "Parse failed: " + e.getMessage());
		}
	}


	public final class MessageHandler extends Handler {

		public MessageHandler(Looper looper) {
			super(looper);
		}

		public void handleMessage(Message msg) {
			final String addr = (String) msg.obj;
			final Device dev = DeviceRegister.device(addr);
			switch (msg.what) {
				case 0: {   // DEVICE CONNECTED
					// Change device status to connected
					dev.setConnected();
					final Intent intent = new Intent("bing");
					LocalBroadcastManager.getInstance(ConnectionService.this).sendBroadcast(intent);
				}
				break;
				case 1: {   // DEVICE DISCONNECTED
					// Stop and remove thread
					stopThread(addr);
					// Change device status to disconnected
					dev.setDisconnected();
					final Intent intent = new Intent("bing");
					LocalBroadcastManager.getInstance(ConnectionService.this).sendBroadcast(intent);
				}
				break;
				case 2: {   // INCOMING DATA
					String data = msg.getData().getString("data");
					parseData(dev, data);
				}
				break;
				default:
					super.handleMessage(msg);
			}
		}

	}
}