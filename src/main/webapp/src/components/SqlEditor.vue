<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import { resolveExecutionText } from '../execution-text'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{
  'update:modelValue': [value: string]
  execute: [sql: string]
}>()

const container = ref<HTMLDivElement | null>(null)
let editor: monaco.editor.IStandaloneCodeEditor | undefined

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
}

const executionText = () => {
  if (!editor) return props.modelValue
  const selection = editor.getSelection()
  const model = editor.getModel()
  const selectedText =
    selection && model && !selection.isEmpty() ? model.getValueInRange(selection) : null
  return resolveExecutionText(editor.getValue(), selectedText)
}

onMounted(() => {
  if (!container.value) return
  monaco.editor.defineTheme('db-console', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: '176B63', fontStyle: 'bold' },
      { token: 'string', foreground: '9A6026' },
      { token: 'number', foreground: '5E62A6' },
      { token: 'comment', foreground: '829089', fontStyle: 'italic' },
      { token: 'operator', foreground: '4E5D57' },
    ],
    colors: {
      'editor.background': '#FFFFFF',
      'editor.foreground': '#263631',
      'editor.lineHighlightBackground': '#F3F7F5',
      'editorLineNumber.foreground': '#A0ACA6',
      'editorLineNumber.activeForeground': '#49655C',
      'editorCursor.foreground': '#176B63',
      'editor.selectionBackground': '#D9ECE5',
      'editor.inactiveSelectionBackground': '#EAF3EF',
      'editorIndentGuide.background1': '#EDF1EF',
      'editorWhitespace.foreground': '#DDE5E1',
    },
  })
  editor = monaco.editor.create(container.value, {
    value: props.modelValue,
    language: 'sql',
    theme: 'db-console',
    ariaLabel: 'SQL 编辑器',
    automaticLayout: true,
    fontFamily: "'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace",
    fontSize: 13,
    lineHeight: 23,
    fontLigatures: false,
    padding: { top: 14, bottom: 14 },
    minimap: { enabled: false },
    lineNumbersMinChars: 3,
    glyphMargin: false,
    folding: true,
    foldingHighlight: false,
    lineDecorationsWidth: 8,
    scrollBeyondLastLine: false,
    smoothScrolling: true,
    cursorSmoothCaretAnimation: 'off',
    renderLineHighlight: 'all',
    overviewRulerBorder: false,
    hideCursorInOverviewRuler: true,
    fixedOverflowWidgets: true,
    scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },
    suggestFontSize: 12,
    suggestLineHeight: 22,
  })
  editor.onDidChangeModelContent(() => emit('update:modelValue', editor?.getValue() ?? ''))
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => emit('execute', executionText()))
})

watch(() => props.modelValue, (value) => {
  if (editor && value !== editor.getValue()) editor.setValue(value)
})

const insertText = (text: string) => {
  if (!editor) return
  const selection = editor.getSelection()
  if (!selection) return
  editor.executeEdits('object-explorer', [{ range: selection, text, forceMoveMarkers: true }])
  editor.focus()
}

const focusEditor = () => editor?.focus()

defineExpose({ insertText, executionText, focusEditor })

onUnmounted(() => editor?.dispose())
</script>

<template>
  <div ref="container" class="sql-editor"></div>
</template>

<style scoped>
.sql-editor {
  min-inline-size: 0;
  min-block-size: 0;
  inline-size: 100%;
  block-size: 100%;
}
</style>
