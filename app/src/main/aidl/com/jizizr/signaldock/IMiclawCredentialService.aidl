package com.jizizr.signaldock;

interface IMiclawCredentialService {
    String getSessionJson(boolean forceRefresh);
    void destroy();
}
