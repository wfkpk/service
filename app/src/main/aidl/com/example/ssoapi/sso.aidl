
package com.example.ssoapi;

import com.example.ssoapi.Account;
import com.example.ssoapi.IAuthCallback;

interface sso {
    void login(String mail, String password, IAuthCallback callback);
    void register(String mail, String password, IAuthCallback callback);
    void logout(String guid);
    void logoutAll();
    void switchAccount(String guid);
    Account getActiveAccount();
    List<Account> getAllAccounts();
    void fetchToken(String mail, String password, IAuthCallback callback);
    void fetchAccountInfo(String guid, String sessionToken, IAuthCallback callback);
}
