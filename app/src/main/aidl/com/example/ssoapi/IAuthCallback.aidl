package com.example.ssoapi;

import com.example.ssoapi.AuthResult;
import com.example.ssoapi.Account;

interface IAuthCallback {
    void onResult(in AuthResult result);
    void onAccountReceived(in Account account);
}
