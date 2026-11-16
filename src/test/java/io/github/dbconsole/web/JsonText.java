package io.github.dbconsole.web;

/** MockMvc JSON 请求体构造共享工具：字符串值转 JSON 字面量（转义反斜杠、引号与换行）。 */
public final class JsonText {

  private JsonText() {}

  public static String jsonText(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }
}
