package com.example.helloworld;

interface IUserService {
    void destroy() = 16; // Shizuku 强制要求建议保留
    void exit() = 1;
    
    // 定义我们要远程执行的任务
    int runCommand(String cmd) = 2;
}