/**
 *
 * Created by wenxi on 16/1/23.
 */

package com.example.bluetooth.library;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;

@SuppressLint("NewApi")
public class BluetoothService {
    // Debugging
    private static final String TAG = "Bluetooth Service";
    
    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "Bluetooth Secure";

    // Unique UUID for this application
    private static final UUID UUID_ANDROID_DEVICE =
            UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
    private static final UUID UUID_OTHER_DEVICE =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    // Member fields
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mSecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;
    private boolean isAndroid = BluetoothState.DEVICE_ANDROID;
        
    // Constructor. Prepares a new BluetoothChat session
    // context : The UI Activity Context
    // handler : A Handler to send messages back to the UI Activity
    public BluetoothService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = BluetoothState.STATE_NONE;
        mHandler = handler;
    }

    
    // Set the current state of the chat connection
    // state : An integer defining the current connection state
    private synchronized void setState(int state) {
        Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;
        
        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(BluetoothState.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    /**
     *   Return the current connection state. 返回当前蓝牙连接的状态
      */

    public synchronized int getState() {
        return mState;
    }

    // Start the chat service. Specifically start AcceptThread to begin a
    // session in listening (server) mode. Called by the Activity onResume() 
    public synchronized void start(boolean isAndroid) {
        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}
        
        setState(BluetoothState.STATE_LISTEN);
        
        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(isAndroid);
            mSecureAcceptThread.start();
            BluetoothService.this.isAndroid = isAndroid;
        }
    }

    /**
     * connet a device 连接到一个设备
     * @param device
     */
    public synchronized void connect(BluetoothDevice device) {
        // finish all Thread 结束mConnectThread的线程
        if (mState == BluetoothState.STATE_CONNECTING) {
            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        }
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(BluetoothState.STATE_CONNECTING);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param device  The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice
            device, final String socketType) {
        // Cancel the thread that completed the connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

        // Cancel the accept thread because we only want to connect to one device
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(BluetoothState.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(BluetoothState.DEVICE_NAME, device.getName());
        bundle.putString(BluetoothState.DEVICE_ADDRESS, device.getAddress());
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        setState(BluetoothState.STATE_CONNECTED);
    }

    // Stop all threads
    public synchronized void stop() {
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread.kill();
            mSecureAcceptThread = null;
        }
        setState(BluetoothState.STATE_NONE);
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * 发送数据函数
     * @param out
     */

    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != BluetoothState.STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    // Indicate that the connection attempt failed and notify the UI Activity
    private void connectionFailed() {
        // Start the service over to restart listening mode
        BluetoothService.this.start(BluetoothService.this.isAndroid);
    }

    // Indicate that the connection was lost and notify the UI Activity
    private void connectionLost() {
        // Start the service over to restart listening mode
        BluetoothService.this.start(BluetoothService.this.isAndroid);
    }

    /**
     * This thread runs while listening for incoming connections.
     * 服务端线程，用来接收客户端的请求
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private BluetoothServerSocket mmServerSocket;
        private String mSocketType;
        boolean isRunning = true;

        public AcceptThread(boolean isAndroid) {
            BluetoothServerSocket tmp = null;

            // Create a new listening server socket
            try {
                if(isAndroid)
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, UUID_OTHER_DEVICE);
                else
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE, UUID_OTHER_DEVICE);
            } catch (IOException e) { }
            mmServerSocket = tmp;
        }

        public void run() {
            setName("AcceptThread" + mSocketType);
            BluetoothSocket socket = null;

            // Listen to the server socket if we're not connected
            while (mState != BluetoothState.STATE_CONNECTED && isRunning) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BluetoothService.this) {
                        switch (mState) {
                        case BluetoothState.STATE_LISTEN:
                        case BluetoothState.STATE_CONNECTING:
                            // Situation normal. Start the connected thread.
                            connected(socket, socket.getRemoteDevice(),
                                    mSocketType);
                            break;
                        case BluetoothState.STATE_NONE:
                        case BluetoothState.STATE_CONNECTED:
                            // Either not ready or already connected. Terminate new socket.
                            try {
                                socket.close();
                            } catch (IOException e) { }
                            break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                mmServerSocket.close();
                mmServerSocket = null;
            } catch (IOException e) { }
        }

        public void kill() {
            isRunning = false;
        }
    }


    /**
     * This thread runs while attempting to make an outgoing connection
     * 客户端线程，向服务端发送请求
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                if(BluetoothService.this.isAndroid)
                    tmp = device.createRfcommSocketToServiceRecord(UUID_OTHER_DEVICE );
                else
                    tmp = device.createRfcommSocketToServiceRecord(UUID_OTHER_DEVICE);
            } catch (IOException e) { }
            mmSocket = tmp;
        }

        public void run() {
            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                e.printStackTrace();
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                  e2.printStackTrace();
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }

    /**
     * This is where the fun begins because you can share data between devices.
     * 发送数据线程
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer;
            ArrayList<Integer> arr_byte = new ArrayList<Integer>();

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    int data = mmInStream.read();
                    if(data == 0x0A) { 
                    } else if(data == 0x0D) {
                        buffer = new byte[arr_byte.size()];
                        for(int i = 0 ; i < arr_byte.size() ; i++) {
                            buffer[i] = arr_byte.get(i).byteValue();
                        }
                        // Send the obtained bytes to the UI Activity
                        mHandler.obtainMessage(BluetoothState.MESSAGE_READ
                                , buffer.length, -1, buffer).sendToTarget();
                        arr_byte = new ArrayList<Integer>();
                    } else {
                        arr_byte.add(data);
                    }
                } catch (IOException e) {
                    connectionLost();
                    // Start the service over to restart listening mode
                    BluetoothService.this.start(BluetoothService.this.isAndroid);
                    break;
                }
            }
        }

        // Write to the connected OutStream.
        // @param buffer  The bytes to write
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);//通过OutStream流发送数据
                // Share the sent message back to the UI Activity
                mHandler.obtainMessage(BluetoothState.MESSAGE_WRITE
                        , -1, -1, buffer).sendToTarget();
            } catch (IOException e) { }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }
}