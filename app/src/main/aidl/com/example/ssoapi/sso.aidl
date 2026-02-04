
package com.example.ssoapi;

import com.example.ssoapi.Account;

interface sso {
    void login(in Account account);
    void logout(String accountId);
    void logoutAll();
    void switchAccount(in Account account);
    Account getActiveAccount();
    List<Account> getAllAccounts();
}
