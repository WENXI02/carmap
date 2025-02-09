package com.example.wenxi.carmap.Fingerprint.server;

/**
 * Created by wenxi on 16/3/23.
 */

import android.text.TextUtils;

import com.example.bluetooth.library.Bluetoothinit;
import com.example.wenxi.carmap.Fingerprint.BaseActivity;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A fake backend implementation of {@link StoreBackend}.
 */
public class StoreBackendImpl implements StoreBackend {

    private final Map<String, PublicKey> mPublicKeys = new HashMap<>();
    private final Map<String,String>mPassword=new HashMap<>();
    private final Set<Transaction> mReceivedTransactions = new HashSet<>();
    private Bluetoothinit bluetoothinit;
    private BaseActivity baseActivity;
    @Override
    public boolean verify(Transaction transaction, byte[] transactionSignature) {
        try {
            if (mReceivedTransactions.contains(transaction)) {
                // It verifies the equality of the transaction including the client nonce
                // So attackers can't do replay attacks.
                return false;
            }
            mReceivedTransactions.add(transaction);
            PublicKey publicKey = mPublicKeys.get(transaction.getUserId());
            Signature verificationFunction = Signature.getInstance("SHA256withECDSA");
            verificationFunction.initVerify(publicKey);
            verificationFunction.update(transaction.toByteArray());
            if (verificationFunction.verify(transactionSignature)) {
                // Transaction is verified with the public key associated with the user
                // Do some post purchase processing in the server

                return true;
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            // In a real world, better to send some error message to the user
        }
        return false;
    }


    @Override
    public boolean verify(Transaction transaction, String password) {
        // As this is just a sample, we always assume that the password is right.
        String input_password=mPassword.get(transaction.getUserId());
        if((!TextUtils.isEmpty(input_password))&& TextUtils.equals(input_password,password)) {
            return true;
        }else{
            return false;
        }
    }

    @Override
    public boolean enroll(String userId, String password, PublicKey publicKey) {
        if (publicKey != null) {
            mPublicKeys.put(userId, publicKey);
        }
        if (!password.isEmpty()){
            mPassword.put(userId,password);
        }
        // We just ignore the provided password here, but in real life, it is registered to the
        // backend.
        return true;
    }

    @Override
    public boolean enroll(String userId, String password, PublicKey publicKey, BaseActivity activity) {
        this.baseActivity=activity;
        if (publicKey != null) {
            mPublicKeys.put(userId, publicKey);
        }
        if (!password.isEmpty()){
            mPassword.put(userId,password);
        }
        // We just ignore the provided password here, but in real life, it is registered to the
        // backend.
        return true;
    }
}
