/**
 * API/未知错误到用户文案的统一收口：Error 且携带 message 时取原文，否则用兜底文案。
 * 调用方（useExecution / useScriptExecution 等编排层）不再各写一份 instanceof 判别。
 */
export function apiErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}
