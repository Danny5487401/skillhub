import ReactDiffViewer, { DiffMethod } from 'react-diff-viewer-continued'

const palette = {
  diffViewerBackground: 'hsl(var(--card))',
  diffViewerColor: 'hsl(var(--foreground))',
  gutterBackground: 'hsl(var(--muted))',
  gutterColor: 'hsl(var(--muted-foreground))',
  codeFoldBackground: 'hsl(var(--muted))',
  addedBackground: 'rgba(16, 185, 129, 0.13)',
  removedBackground: 'rgba(239, 68, 68, 0.13)',
  addedGutterBackground: 'rgba(16, 185, 129, 0.18)',
  removedGutterBackground: 'rgba(239, 68, 68, 0.18)',
}

export default function ReviewFileDiff({ oldText, newText }: { oldText: string; newText: string }) {
  return <ReactDiffViewer oldValue={oldText} newValue={newText} compareMethod={DiffMethod.LINES}
    disableWordDiff splitView={false} showDiffOnly hideSummary
    styles={{ variables: { light: palette, dark: palette }, line: { fontSize: '12px' } }} />
}
