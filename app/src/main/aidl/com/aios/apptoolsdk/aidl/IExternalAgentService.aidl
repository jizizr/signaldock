package com.aios.apptoolsdk.aidl;

import com.aios.apptoolsdk.aidl.Attachment;
import com.aios.apptoolsdk.aidl.IExternalAgentCallback;

interface IExternalAgentService {
    String openSession(String appMetaJson, boolean persistent);
    void closeSession(String sessionId);
    void submit(
        String sessionId,
        String requestJson,
        in List<Attachment> attachments,
        IExternalAgentCallback callback
    );
}
