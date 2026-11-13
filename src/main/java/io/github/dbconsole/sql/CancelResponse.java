package io.github.dbconsole.sql;

/** 取消请求结果（F-14）：kind 区分取消成功、查询先完成与取消失败。 */
public record CancelResponse(String kind, String message) {

  public static final String KIND_CANCELLED = "CANCELLED";
  public static final String KIND_COMPLETED = "COMPLETED";
  public static final String KIND_FAILED = "FAILED";

  public static CancelResponse cancelled() {
    return new CancelResponse(KIND_CANCELLED, null);
  }

  public static CancelResponse completed() {
    return new CancelResponse(KIND_COMPLETED, null);
  }

  public static CancelResponse failed(String message) {
    return new CancelResponse(KIND_FAILED, message);
  }
}
