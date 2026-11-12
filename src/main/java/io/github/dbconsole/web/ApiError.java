package io.github.dbconsole.web;

/** 统一错误响应：code 供前端分派，message 面向用户且不泄漏内部细节。 */
public record ApiError(String code, String message) {}
