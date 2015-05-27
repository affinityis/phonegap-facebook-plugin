package org.apache.cordova.facebook;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CordovaInterface;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.net.Uri;

import com.facebook.*;
import com.facebook.appevents.AppEventsLogger;
import com.facebook.internal.BundleJSONConverter;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.facebook.login.widget.ProfilePictureView;
import com.facebook.share.ShareApi;
import com.facebook.share.Sharer;
import com.facebook.share.internal.ShareInternalUtility;
import com.facebook.share.model.SharePhoto;
import com.facebook.share.model.SharePhotoContent;
import com.facebook.share.model.ShareLinkContent;
import com.facebook.share.widget.ShareDialog;
import com.facebook.share.widget.AppInviteDialog;
import com.facebook.share.model.AppInviteContent;
import com.facebook.share.widget.GameRequestDialog;
import com.facebook.share.model.GameRequestContent;

public class ConnectPlugin extends CordovaPlugin {

    private static final int INVALID_ERROR_CODE = -2; // -1 is FacebookRequestError.INVALID_ERROR_CODE
    private static final String PUBLISH_PERMISSION_PREFIX = "publish";
    private static final String MANAGE_PERMISSION_PREFIX = "manage";
    @SuppressWarnings("serial")
    private static final Set<String> OTHER_PUBLISH_PERMISSIONS = new HashSet<String>() {
        {
            add("ads_management");
            add("create_event");
            add("rsvp_event");
            // TODO: Any changes?
        }
    };
    private static final String TAG = "ConnectPlugin";

    private AppEventsLogger logger;
    private String applicationId = null;
    private CallbackContext loginContext = null;
    private CallbackContext showDialogContext = null;
    private CallbackContext graphContext = null;
    private CallbackManager callbackManager = null;
    private Bundle paramBundle;
    private String method;
    private String graphPath;
    private String userID;
    private boolean trackingPendingCall = false;
    private static boolean initialized = false;

    public static void initializeFacebookSdkWithApplication(Application app) {
        // Initialize the SDK
        FacebookSdk.sdkInitialize(app);
        // TODO: LoggingBehavior.GRAPH_API_DEBUG_INFO
        // TODO: LoggingBehavior.GRAPH_API_DEBUG_WARNING
        initialized = true;
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        // Init logger
        logger = AppEventsLogger.newLogger(cordova.getActivity());

        // Set up the activity result callback to this class
        cordova.setActivityResultCallback(this);

        // Initialize callback manager
        callbackManager = CallbackManager.Factory.create();

        // Setup session callbacks
        LoginManager.getInstance().registerCallback(callbackManager,
            new FacebookCallback<LoginResult>() {
                @Override
                public void onSuccess(LoginResult loginResult) {
                    Log.i(TAG, "LoginManager onSuccess");
                    final AccessToken accessToken = loginResult.getAccessToken();
                    updateWithToken(accessToken);
                    if (loginContext != null) {
                        loginContext.success(getResponse());
                    }
                }

                @Override
                public void onCancel() {
                    Log.i(TAG, "LoginManager onCancel");
                    if (loginContext != null) {
                        loginContext.error("Login cancelled.");
                    }
                }

                @Override
                public void onError(FacebookException e) {
                    Log.i(TAG, "LoginManager onError");
                    loginContext.error("Login error.");
                }
        });

        // Setup token tracker
        AccessTokenTracker accessTokenTracker = new AccessTokenTracker() {
            @Override
            protected void onCurrentAccessTokenChanged(AccessToken oldAccessToken, AccessToken newAccessToken) {
                Log.d(TAG, "access token tracker");
                updateWithToken(newAccessToken);
            }
        };

        super.initialize(cordova, webView);
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        // Observe how frequently users activate their app by logging an app activation event.
        AppEventsLogger.activateApp(cordova.getActivity());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        Log.d(TAG, "activity result in plugin: requestCode(" + requestCode + "), resultCode(" + resultCode + ")");

        // Required for SDK callbacks to function
        callbackManager.onActivityResult(requestCode, resultCode, intent);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        // Get the current access token
        final AccessToken accessToken = AccessToken.getCurrentAccessToken();

        /*
         * Initialize
         */
        if (action.equals("initialize")) {
            Log.d(TAG, "initialize FB");
        }

        /**
         * Login
         */
        if (action.equals("login")) {
            Log.d(TAG, "login FB");
            // Get the permissions
            String[] arrayPermissions = new String[args.length()];
            for (int i = 0; i < args.length(); i++) {
                arrayPermissions[i] = args.getString(i);
            }

            List<String> permissions = null;
            if (arrayPermissions.length > 0) {
                permissions = Arrays.asList(arrayPermissions);
            }

            // Set a pending callback to cordova
            loginContext = callbackContext;
            PluginResult pr = new PluginResult(PluginResult.Status.NO_RESULT);
            pr.setKeepCallback(true);
            loginContext.sendPluginResult(pr);

            // Check for active access token
            if (accessToken != null) {
                // Reauthorize flow
                boolean publishPermissions = false;
                boolean readPermissions = false;
                // Figure out if this will be a read or publish reauthorize
                if (permissions == null) {
                    // No permissions, read
                    readPermissions = true;
                }
                // Loop through the permissions to see what
                // is being requested
                for (String permission : arrayPermissions) {
                    if (isPublishPermission(permission)) {
                        publishPermissions = true;
                    } else {
                        readPermissions = true;
                    }
                    // Break if we have a mixed bag, as this is an error
                    if (publishPermissions && readPermissions) {
                        break;
                    }
                }
                if (publishPermissions && readPermissions) {
                    callbackContext.error("Cannot ask for both read and publish permissions.");
                } else {
                    // Set up the activity result callback to this class
                    cordova.setActivityResultCallback(this);
                    // Check for write permissions, the default is read (empty)
                    if (publishPermissions) {
                        // Request new publish permissions
                        LoginManager.getInstance().logInWithPublishPermissions(cordova.getActivity(), permissions);
                    } else {
                        // Request new read permissions
                        LoginManager.getInstance().logInWithReadPermissions(cordova.getActivity(), permissions);
                    }
                }
            }
            // Initial login
            else {
                // Set up the activity result callback to this class
                cordova.setActivityResultCallback(this);
                // Only ask for read permissions initially
                LoginManager.getInstance().logInWithReadPermissions(cordova.getActivity(), permissions);
            }
            return true;
        }

        /**
         * Logout
         */
        else if (action.equals("logout")) {
            LoginManager.getInstance().logOut();
            userID = null;
            callbackContext.success();
            return true;
        }

        /**
         * getLoginStatus
         */
        else if (action.equals("getLoginStatus")) {
            if (userID == null && accessToken != null) {
                Log.e(TAG, "Active token but no user ID");
                callbackContext.error("Active token but no user ID.");
            } else {
                callbackContext.success(getResponse());
            }
            return true;
        }

        /**
         * getAccessToken
         */
        else if (action.equals("getAccessToken")) {
            if (accessToken != null) {
                callbackContext.success(accessToken.getToken());
            } else {
                callbackContext.error("AccessToken is null.");
            }
            return true;
        }

        /**
         * logEvent
         */
        else if (action.equals("logEvent")) {
            if (args.length() == 0) {
                // Not enough parameters
                callbackContext.error("Invalid arguments");
                return true;
            }
            String eventName = args.getString(0);
            if (args.length() == 1) {
                logger.logEvent(eventName);
            } else {
                // Arguments is greater than 1
                JSONObject params = args.getJSONObject(1);
                Bundle parameters = new Bundle();

                Iterator<?> iterator = params.keys();
                while (iterator.hasNext()) {
                    try {
                        // Try get a String
                        String key = (String) iterator.next();
                        String value = params.getString(key);
                        parameters.putString(key, value);
                    } catch (Exception e) {
                        // Maybe it was an int
                        Log.w(TAG, "Type in AppEvent parameters was not String for key: " + (String) iterator.next());
                        try {
                            String key = (String) iterator.next();
                            int value = params.getInt(key);
                            parameters.putInt(key, value);
                        } catch (Exception e2) {
                            // Nope
                            Log.e(TAG, "Unsupported type in AppEvent parameters for key: " + (String) iterator.next());
                        }
                    }
                }
                if (args.length() == 2) {
                    logger.logEvent(eventName, parameters);
                }
                if (args.length() == 3) {
                    double value = args.getDouble(2);
                    logger.logEvent(eventName, value, parameters);
                }
            }
            callbackContext.success();
            return true;
        }

        /**
         * logPurchase
         */
        else if (action.equals("logPurchase")) {
            // While calls to logEvent can be made to register purchase events,
            // there is a helper method that explicitly takes a currency indicator.
            if (args.length() != 2) {
                callbackContext.error("Invalid arguments.");
                return true;
            }
            int value = args.getInt(0);
            String currency = args.getString(1);
            logger.logPurchase(BigDecimal.valueOf(value), Currency.getInstance(currency));
            callbackContext.success();
            return true;
        }

        /**
         * showDialog
         */
        else if (action.equals("showDialog")) {
            Bundle collect = new Bundle();
            JSONObject params = null;
            try {
                params = args.getJSONObject(0);
            } catch (JSONException e) {
                params = new JSONObject();
            }

            final ConnectPlugin me = this;
            Iterator<?> iter = params.keys();
            while (iter.hasNext()) {
                String key = (String) iter.next();
                if (key.equals("method")) {
                    try {
                        this.method = params.getString(key);
                    } catch (JSONException e) {
                        Log.w(TAG, "Nonstring method parameter provided to dialog");
                    }
                } else {
                    try {
                        collect.putString(key, params.getString(key));
                    } catch (JSONException e) {
                        // Need to handle JSON parameters
                        Log.w(TAG, "Non-string parameter provided to dialog discarded");
                    }
                }
            }
            this.paramBundle = new Bundle(collect);

            // The Share dialog prompts a person to publish an individual story or an Open Graph story to their timeline.
            // This does not require Facebook Login or any extended permissions, so it is the easiest way to enable sharing on web.
            boolean isShareDialog = this.method.equalsIgnoreCase("share") ||
                this.method.equalsIgnoreCase("share_open_graph") ||
                this.method.equalsIgnoreCase("feed") ||
                this.method.equalsIgnoreCase("send");

            // Must be an active session when is not a Shared dialog or if the Share dialog cannot be presented.
            boolean requiresAnActiveToken = (!isShareDialog);
            if (requiresAnActiveToken) {
                if (accessToken == null) {
                    callbackContext.error("No active token.");
                    return true;
                }
                /*else if (!accessToken.getPermissions().contains("publish_actions")) {
                    callbackContext.error("Required permission: publish_actions");
                    return true;
                }*/
            }

            // Begin by sending a callback pending notice to Cordova
            showDialogContext = callbackContext;
            PluginResult pr = new PluginResult(PluginResult.Status.NO_RESULT);
            pr.setKeepCallback(true);
            showDialogContext.sendPluginResult(pr);

            /*
             * Sharing
             */
            if (isShareDialog) {
                // TODO: Better way to test multiple keys?
                String title = (paramBundle.getString("title") != null) ? paramBundle.getString("title") : paramBundle.getString("name");
                String url = (paramBundle.getString("link") != null) ? paramBundle.getString("link") : paramBundle.getString("href");

                ShareLinkContent shareContent = new ShareLinkContent.Builder()
                        .setContentTitle(title)
                        .setContentDescription(paramBundle.getString("description"))
                        .setContentUrl(Uri.parse(url))
                        .build();

                ShareDialog shareDialog = new ShareDialog(cordova.getActivity());
                shareDialog.registerCallback(callbackManager,
                    new FacebookCallback<Sharer.Result>() {
                        @Override
                        public void onSuccess(Sharer.Result result) {
                            Log.i(TAG, "ShareDialog FacebookCallback onSuccess");
                            // FINISH
                        }

                        @Override
                        public void onCancel() {
                            Log.i(TAG, "ShareDialog FacebookCallback onCancel");
                            // FINISH
                        }

                        @Override
                        public void onError(FacebookException e) {
                            Log.i(TAG, "ShareDialog FacebookCallback onError");
                            // FINISH
                        }
                });

                if (ShareDialog.canShow(ShareLinkContent.class)) {
                    shareDialog.show(shareContent);
                }
            }

            /*
             * App Invites
             */
            else if (this.method.equalsIgnoreCase("appinvites")) {
                if (AppInviteDialog.canShow()) {
                    AppInviteContent content = new AppInviteContent.Builder()
                            .setApplinkUrl(paramBundle.getString("link"))
                            .setPreviewImageUrl(paramBundle.getString("preview"))
                            .build();

                    AppInviteDialog appInviteDialog = new AppInviteDialog(cordova.getActivity());
                    appInviteDialog.registerCallback(callbackManager,
                        new FacebookCallback<AppInviteDialog.Result>() {
                            @Override
                            public void onSuccess(AppInviteDialog.Result result) {
                                Log.i(TAG, "AppInviteDialog onSuccess");
                                try {
                                    final JSONObject json = BundleJSONConverter.convertToJSON(result.getData());
                                    showDialogContext.success(json);
                                } catch(JSONException e) {
                                    Log.e(TAG, "JSONException");
                                }

                            }

                            @Override
                            public void onCancel() {
                                Log.i(TAG, "AppInviteDialog onCancel");
                                showDialogContext.error("User cancelled dialog.");
                            }

                            @Override
                            public void onError(FacebookException e) {
                                Log.i(TAG, "AppInviteDialog onError");
                                // FINISH
                            }
                    });
                    appInviteDialog.show(content);
                }
            }

            /*
             * Game Requests
             */
            else if (this.method.equalsIgnoreCase("apprequests")) {
                // FINISH
            }

            /*
             * Join Game Group
             */
            else if (this.method.equalsIgnoreCase("game_group_create")) {
                // FINISH
            }

            else {
                callbackContext.error("Unsupported dialog method.");
            }

            return true;
        }

        /**
         * graphApi
         */
        else if (action.equals("graphApi")) {
            graphContext = callbackContext;
            PluginResult pr = new PluginResult(PluginResult.Status.NO_RESULT);
            pr.setKeepCallback(true);
            graphContext.sendPluginResult(pr);

            graphPath = args.getString(0);
            JSONArray arr = args.getJSONArray(1);

            final List<String> permissionsList = new ArrayList<String>();
            for (int i = 0; i < arr.length(); i++) {
                permissionsList.add(arr.getString(i));
            }

            boolean publishPermissions = false;
            boolean readPermissions = false;
            if (permissionsList.size() > 0) {
                for (String permission : permissionsList) {
                    if (isPublishPermission(permission)) {
                        publishPermissions = true;
                    } else {
                        readPermissions = true;
                    }
                    // Break if we have a mixed bag, as this is an error
                    if (publishPermissions && readPermissions) {
                        break;
                    }
                }
                if (publishPermissions && readPermissions) {
                    graphContext.error("Cannot ask for both read and publish permissions.");
                } else {
                    if (accessToken.getPermissions().containsAll(permissionsList)) {
                        makeGraphCall();
                    } else {
                        // Set up the activity result callback to this class
                        // TODO: Complete the graph call?
                        cordova.setActivityResultCallback(this);
                        // Check for write permissions, the default is read (empty)
                        if (publishPermissions) {
                            // Request new publish permissions
                            LoginManager.getInstance().logInWithPublishPermissions(cordova.getActivity(), permissionsList);
                        } else {
                            // Request new read permissions
                            LoginManager.getInstance().logInWithReadPermissions(cordova.getActivity(), permissionsList);
                        }
                    }
                }
            } else {
                makeGraphCall();
            }
            return true;
        }

        return false;
    }

    private void updateWithToken(AccessToken accessToken) {
        GraphRequestAsyncTask request = GraphRequest.newMeRequest(accessToken, new GraphRequest.GraphJSONObjectCallback() {
            @Override
            public void onCompleted(JSONObject user, GraphResponse graphResponse) {
                // Record user id
                userID = user.optString("id");
            }
        }).executeAsync();
    }

    private void makeGraphCall() {
        GraphRequest.Callback graphCallback = new GraphRequest.Callback() {

            @Override
            public void onCompleted(GraphResponse response) {
                if (graphContext != null) {
                    if (response.getError() != null) {
                        graphContext.error(getFacebookRequestErrorResponse(response.getError()));
                    } else {
                        graphContext.success(response.getJSONObject());
                    }
                    graphPath = null;
                    graphContext = null;
                }
            }
        };

        // If you're using the paging URLs they will be URLEncoded, let's decode them.
        try {
            graphPath = URLDecoder.decode(graphPath, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        String[] urlParts = graphPath.split("\\?");
        String graphAction = urlParts[0];
        GraphRequest graphRequest = GraphRequest.newGraphPathRequest(null, graphAction, graphCallback);
        Bundle params = graphRequest.getParameters();

        if (urlParts.length > 1) {
            String[] queries = urlParts[1].split("&");

            for (String query : queries) {
                int splitPoint = query.indexOf("=");
                if (splitPoint > 0) {
                    String key = query.substring(0, splitPoint);
                    String value = query.substring(splitPoint + 1, query.length());
                    params.putString(key, value);
                }
            }
        }

        AccessToken token = AccessToken.getCurrentAccessToken();
        params.putString("access_token", token.getToken());

        graphRequest.setParameters(params);
        graphRequest.executeAsync();
    }

    /*
     * Checks for publish permissions
     */
    private boolean isPublishPermission(String permission) {
        return permission != null &&
            (permission.startsWith(PUBLISH_PERMISSION_PREFIX) ||
            permission.startsWith(MANAGE_PERMISSION_PREFIX) ||
            OTHER_PUBLISH_PERMISSIONS.contains(permission));
    }

    /**
     * Create a Facebook Response object that matches the one for the Javascript SDK
     * @return JSONObject - the response object
     */
    public JSONObject getResponse() {
        String response;
        final AccessToken token = AccessToken.getCurrentAccessToken();
        if (token != null) {
            Date today = new Date();
            long expiresTimeInterval = (token.getExpires().getTime() - today.getTime()) / 1000L;
            long expiresIn = (expiresTimeInterval > 0) ? expiresTimeInterval : 0;
            response = "{"
                + "\"status\": \"connected\","
                + "\"authResponse\": {"
                + "\"accessToken\": \"" + token.getToken() + "\","
                + "\"expiresIn\": \"" + expiresIn + "\","
                + "\"session_key\": true,"
                + "\"sig\": \"...\","
                + "\"userID\": \"" + userID + "\""
                + "}"
                + "}";
        } else {
            response = "{"
                + "\"status\": \"unknown\""
                + "}";
        }
        try {
            return new JSONObject(response);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return new JSONObject();
    }

    public JSONObject getFacebookRequestErrorResponse(FacebookRequestError error) {

        String response = "{"
            + "\"errorCode\": \"" + error.getErrorCode() + "\","
            + "\"errorType\": \"" + error.getErrorType() + "\","
            + "\"errorMessage\": \"" + error.getErrorMessage() + "\"";

        String errorUserMessage = error.getErrorUserMessage();

        // Safe check for null
        if (errorUserMessage != null) {
            response += ",\"errorUserMessage\": \"" + errorUserMessage + "\"";
        }

        response += "}";

        try {
            return new JSONObject(response);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return new JSONObject();
    }

    public JSONObject getErrorResponse(Exception error, String message, int errorCode) {

        if (error instanceof FacebookServiceException) {
            return getFacebookRequestErrorResponse(((FacebookServiceException) error).getRequestError());
        }

        String response = "{";

        if (error instanceof FacebookDialogException) {
            errorCode = ((FacebookDialogException) error).getErrorCode();
        }

        if (errorCode != INVALID_ERROR_CODE) {
            response += "\"errorCode\": \"" + errorCode + "\",";
        }

        if (message == null) {
            message = error.getMessage();
        }

        response += "\"errorMessage\": \"" + message + "\"}";

        try {
            return new JSONObject(response);
        } catch (JSONException e) {

            e.printStackTrace();
        }
        return new JSONObject();
    }
}
