package com.dpad.mgr.priv;

import com.dpad.mgr.priv.ILineCallback;

interface IUserService {
    Bundle exec(in String[] argv);
    Bundle execTimeout(in String[] argv, int timeoutMs);
    int spawn(in String[] argv, String pidfile);
    void kill(int pid);
    boolean isAlive(int pid);
    int exitCode(int pid);
    boolean writeFile(String path, String content);
    boolean setPointerIconType(int type);
    void startTail(ILineCallback cb);
    void stopTail();
    void destroy();
}
