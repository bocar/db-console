/** F-04：有选区时执行选区，无选区执行全文。 */
export function resolveExecutionText(fullText: string, selectedText: string | null): string {
  return selectedText !== null && selectedText !== '' ? selectedText : fullText
}
