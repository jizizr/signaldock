package com.aios.apptoolsdk.aidl;

import com.aios.apptoolsdk.aidl.Attachment;

interface IExternalAgentCallback {
    void onTextDelta(String sessionId, String delta);
    void onComplete(String sessionId, String resultJson, in List<Attachment> attachments);
    void onError(String sessionId, String errorJson);
    void onReasoningDelta(String sessionId, String delta);
    void onToolEvent(String sessionId, String eventJson);
    void onTtsEvent(String sessionId, String eventJson);
}
