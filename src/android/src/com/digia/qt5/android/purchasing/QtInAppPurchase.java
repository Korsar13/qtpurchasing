/****************************************************************************
**
** Copyright (C) 2014 Digia Plc
** All rights reserved.
** For any questions to Digia, please use contact form at http://qt.digia.com
**
** This file is part of the Qt Purchasing Add-on.
**
** $QT_BEGIN_LICENSE$
** Licensees holding valid Qt Commercial licenses may use this file in
** accordance with the Qt Commercial License Agreement provided with the
** Software or, alternatively, in accordance with the terms contained in
** a written agreement between you and Digia.
**
** If you have questions regarding the use of this file, please use
** contact form at http://qt.digia.com
** $QT_END_LICENSE$
**
****************************************************************************/

package com.digia.qt5.android.purchasing;

import java.util.ArrayList;
import android.app.PendingIntent;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import org.json.JSONObject;
import org.json.JSONException;


import com.android.vending.billing.IInAppBillingService;

public class QtInAppPurchase
{
    private Context m_context = null;
    private IInAppBillingService m_service = null;
    private String m_publicKey = null;
    private long m_nativePointer;

    public static final int RESULT_OK = 0;
    public static final int RESULT_USER_CANCELED = 1;
    public static final int RESULT_BILLING_UNAVAILABLE = 3;
    public static final int RESULT_ITEM_UNAVAILABLE = 4;
    public static final int RESULT_DEVELOPER_ERROR = 5;
    public static final int RESULT_ERROR = 6;
    public static final int RESULT_ITEM_ALREADY_OWNED = 7;
    public static final int RESULT_ITEM_NOT_OWNED = 8;
    public static final int RESULT_QTPURCHASING_ERROR = 9; // No match with any already defined response codes
    public static final String TAG = "QtInAppPurchase";
    public static final String TYPE_INAPP = "inapp";
    public static final int IAP_VERSION = 3;

    // Should be in sync with QInAppTransaction::FailureReason
    public static final int FAILUREREASON_NOFAILURE    = 0;
    public static final int FAILUREREASON_USERCANCELED = 1;
    public static final int FAILUREREASON_ERROR        = 2;

    private ServiceConnection m_serviceConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service)
        {
            m_service = IInAppBillingService.Stub.asInterface(service);
            try {
                int response = m_service.isBillingSupported(3, m_context.getPackageName(), TYPE_INAPP);
                if (response != RESULT_OK) {
                    Log.e(TAG, "In-app billing not supported");
                    return;
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            // Asynchronously populate list of purchased products
            final Handler handler = new Handler();
            Thread thread = new Thread(new Runnable()
            {
                public void run()
                {
                    queryPurchasedProducts();
                    handler.post(new Runnable()
                    {
                        public void run() { purchasedProductsQueried(m_nativePointer); }
                    });
                }
            });
            thread.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name)
        {
            m_service = null;
        }
    };

    public QtInAppPurchase(Context context, long nativePointer)
    {
        m_context = context;
        m_nativePointer = nativePointer;
    }

    public void initializeConnection()
    {

        Intent serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        if (!m_context.getPackageManager().queryIntentServices(serviceIntent, 0).isEmpty()) {
            m_context.bindService(serviceIntent, m_serviceConnection, Context.BIND_AUTO_CREATE);
        } else {
            Log.e(TAG, "No in-app billing service available.");
            purchasedProductsQueried(m_nativePointer);
        }
    }

    private int bundleResponseCode(Bundle bundle)
    {
        Object o = bundle.get("RESPONSE_CODE");
        if (o == null) {
            // Works around known issue where the response code is not bundled.
            return RESULT_OK;
        } else if (o instanceof Integer) {
            return ((Integer)o).intValue();
        } else if (o instanceof Long) {
            return (int)((Long)o).longValue();
        }

        Log.e(TAG, "Unexpected result for response code: " + o);
        return RESULT_QTPURCHASING_ERROR;
    }

    private void queryPurchasedProducts()
    {
        if (m_service == null) {
            Log.e(TAG, "queryPurchasedProducts: Service not initialized");
            return;
        }

        String continuationToken = null;
        try {
            do {
                Bundle ownedItems = m_service.getPurchases(IAP_VERSION,
                                                           m_context.getPackageName(),
                                                           TYPE_INAPP,
                                                           continuationToken);
                int responseCode = bundleResponseCode(ownedItems);
                if (responseCode != RESULT_OK) {
                    Log.e(TAG, "queryPurchasedProducts: Failed to query purchases products");
                    return;
                }

                ArrayList<String> dataList = ownedItems.getStringArrayList("INAPP_PURCHASE_DATA_LIST");
                if (dataList == null) {
                    Log.e(TAG, "queryPurchasedProducts: No data list in bundle");
                    return;
                }

                ArrayList<String> signatureList = ownedItems.getStringArrayList("INAPP_DATA_SIGNATURE_LIST");
                if (signatureList == null) {
                    Log.e(TAG, "queryPurchasedProducts: No signature list in bundle");
                    return;
                }

                if (dataList.size() != signatureList.size()) {
                    Log.e(TAG, "queryPurchasedProducts: Mismatching sizes of lists in bundle");
                    return;
                }

                for (int i=0; i<dataList.size(); ++i) {
                    String data = dataList.get(i);
                    String signature = signatureList.get(i);

                    if (m_publicKey != null && !Security.verifyPurchase(m_publicKey, data, signature)) {
                        Log.e(TAG, "queryPurchasedProducts: Cannot verify signature of purchase");
                        return;
                    } else {
                        try {
                            JSONObject jo = new JSONObject(data);
                            String productId = jo.getString("productId");
                            int purchaseState = jo.getInt("purchaseState");
                            String purchaseToken = jo.getString("purchaseToken");
                            String orderId = jo.getString("orderId");
                            long timestamp = jo.getLong("purchaseTime");

                            if (purchaseState == 0)
                                registerPurchased(m_nativePointer, productId, signature, data, purchaseToken, orderId, timestamp);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }

                continuationToken = ownedItems.getString("INAPP_CONTINUATION_TOKEN");

            } while (continuationToken != null && continuationToken.length() > 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

    }

    private void queryDetails(final String productId)
    {
        if (m_service == null) {
            Log.e(TAG, "queryDetails: Service not initialized");
            queryFailed(m_nativePointer, productId);
            return;
        }

        // Asynchronously query details about product
        Thread thread = new Thread(new Runnable()
        {
            public void run()
            {
                try {
                    ArrayList<String> productIds = new ArrayList<String>();
                    productIds.add(productId);
                    Bundle productIdBundle = new Bundle();
                    productIdBundle.putStringArrayList("ITEM_ID_LIST", productIds);

                    Bundle bundle = m_service.getSkuDetails(IAP_VERSION,
                                                            m_context.getPackageName(),
                                                            "inapp",
                                                            productIdBundle);
                    int responseCode = bundleResponseCode(bundle);
                    if (responseCode != RESULT_OK) {
                        Log.e(TAG, "queryDetails: Couldn't retrieve sku details.");
                        queryFailed(m_nativePointer, productId);
                        return;
                    }

                    ArrayList<String> detailsList = bundle.getStringArrayList("DETAILS_LIST");
                    if (detailsList == null) {
                        Log.e(TAG, "queryDetails: No details list in response.");
                        queryFailed(m_nativePointer, productId);
                        return;
                    }

                    for (String details : detailsList) {
                        try {
                            JSONObject jo = new JSONObject(details);
                            String queriedProductId = jo.getString("productId");
                            String queriedPrice = jo.getString("price");
                            String queriedTitle = jo.getString("title");
                            String queriedDescription = jo.getString("description");
                            if (queriedProductId == null || queriedPrice == null || queriedTitle == null || queriedDescription == null) {
                                Log.e(TAG, "Data missing from product details.");
                            } else if (productId.equals(queriedProductId)) {
                                registerProduct(m_nativePointer,
                                                queriedProductId,
                                                queriedPrice,
                                                queriedTitle,
                                                queriedDescription);
                                return;
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }

                    }

                    queryFailed(m_nativePointer, productId);
                } catch (RemoteException e) {
                    e.printStackTrace();
                    queryFailed(m_nativePointer, productId);
                }
            }
        });
        thread.start();
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data, String expectedIdentifier)
    {
        if (data == null) {
            purchaseFailed(requestCode, FAILUREREASON_ERROR, "Data missing from result");
            return;
        }

        int responseCode = data.getIntExtra("RESPONSE_CODE", -1);
        String purchaseData = data.getStringExtra("INAPP_PURCHASE_DATA");
        String dataSignature = data.getStringExtra("INAPP_DATA_SIGNATURE");
        String purchaseToken = "";
        String orderId = "";
        long timestamp = 0;

        if (responseCode == RESULT_USER_CANCELED) {
            purchaseFailed(requestCode, FAILUREREASON_USERCANCELED, "");
            return;
        } else if (responseCode != RESULT_OK) {
            String errorString;
            switch (responseCode) {
                case RESULT_BILLING_UNAVAILABLE: errorString = "Billing unavailable"; break;
                case RESULT_ITEM_UNAVAILABLE: errorString = "Item unavailable"; break;
                case RESULT_DEVELOPER_ERROR: errorString = "Developer error"; break;
                case RESULT_ERROR: errorString = "Fatal error occurred"; break;
                case RESULT_ITEM_ALREADY_OWNED: errorString = "Item already owned"; break;
                default: errorString = "Unknown billing error " + responseCode; break;
            };

            purchaseFailed(requestCode, FAILUREREASON_ERROR, errorString);
            return;
        }

        try {
            if (m_publicKey != null && !Security.verifyPurchase(m_publicKey, purchaseData, dataSignature)) {
                purchaseFailed(requestCode, FAILUREREASON_ERROR, "Signature could not be verified");
                return;
            }

            JSONObject jo = new JSONObject(purchaseData);
            String sku = jo.getString("productId");
            if (!sku.equals(expectedIdentifier)) {
                purchaseFailed(requestCode, FAILUREREASON_ERROR, "Unexpected identifier in result");
                return;
            }

            int purchaseState = jo.getInt("purchaseState");
            if (purchaseState != 0) {
                purchaseFailed(requestCode, FAILUREREASON_ERROR, "Unexpected purchase state in result");
                return;
            }

            purchaseToken = jo.getString("purchaseToken");
            orderId = jo.getString("orderId");
            timestamp = jo.getLong("purchaseTime");

        } catch (Exception e) {
            e.printStackTrace();
            purchaseFailed(requestCode, FAILUREREASON_ERROR, e.getMessage());
        }

        purchaseSucceeded(requestCode, dataSignature, purchaseData, purchaseToken, orderId, timestamp);
    }

    public void setPublicKey(String publicKey)
    {
        m_publicKey = publicKey;
    }

    public IntentSender createBuyIntentSender(String identifier)
    {
        if (m_service == null) {
            Log.e(TAG, "Unable to create buy intent. No IAP service connection.");
            return null;
        }

        try {
             Bundle purchaseBundle = m_service.getBuyIntent(3,
                                                           m_context.getPackageName(),
                                                           identifier,
                                                           TYPE_INAPP,
                                                           identifier);
             int response = bundleResponseCode(purchaseBundle);
             if (response != RESULT_OK) {
                 Log.e(TAG, "Unable to create buy intent. Response code: " + response);
                 return null;
             }

             PendingIntent pendingIntent = purchaseBundle.getParcelable("BUY_INTENT");
             return pendingIntent.getIntentSender();
       } catch (Exception e) {
           e.printStackTrace();
           return null;
       }
    }

    public void consumePurchase(String purchaseToken)
    {
        if (m_service == null) {
            Log.e(TAG, "consumePurchase: Unable to consume purchase. No IAP service connection.");
            return;
        }

        try {
            int response = m_service.consumePurchase(3, m_context.getPackageName(), purchaseToken);
            if (response != RESULT_OK) {
                Log.e(TAG, "consumePurchase: Unable to consume purchase. Response code: " + response);
                return;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void purchaseFailed(int requestCode, int failureReason, String errorString) { purchaseFailed(m_nativePointer, requestCode, failureReason, errorString); }
    private void purchaseSucceeded(int requestCode,
                                   String signature,
                                   String purchaseData,
                                   String purchaseToken,
                                   String orderId,
                                   long timestamp)
    {
        purchaseSucceeded(m_nativePointer, requestCode, signature, purchaseData, purchaseToken, orderId, timestamp);
    }

    private native static void queryFailed(long nativePointer, String productId);
    private native static void purchasedProductsQueried(long nativePointer);
    private native static void registerProduct(long nativePointer,
                                               String productId,
                                               String price,
                                               String title,
                                               String description);
    private native static void purchaseFailed(long nativePointer, int requestCode, int failureReason, String errorString);
    private native static void purchaseSucceeded(long nativePointer,
                                                 int requestCode,
                                                 String signature,
                                                 String data,
                                                 String purchaseToken,
                                                 String orderId,
                                                 long timestamp);
    private native static void registerPurchased(long nativePointer,
                                                 String identifier,
                                                 String signature,
                                                 String data,
                                                 String purchaseToken,
                                                 String orderId,
                                                 long timestamp);
}