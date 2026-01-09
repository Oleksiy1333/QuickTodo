# Changelog

All notable changes to QuickTodo will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.8] - 2026-01-09

### Added
- **Build with Claude** - AI-powered task automation via Claude Code CLI with configurable execution modes (Plan/Accept Edits/Skip Permissions) and model selection (Opus 4.5/Sonnet 4)
- **Task descriptions** - Add detailed notes to any task
- **Copy tasks** - Copy tasks with subtasks and descriptions preserved
- **Recent todos popup** - Quick navigation with Ctrl+F1 (Cmd+F1 on macOS)
- **Configurable tooltip behavior** - Choose between hover display, smart truncation, or full text
- **Auto-pause focus timer** - Automatically pause when IDE goes idle (configurable timeout)
- **Independent task time tracking** - Optional hierarchy accumulation for subtask time
- **New task position setting** - Configure whether new tasks appear at top or bottom of list

### Changed
- Improved toolbar layout and actions
- Enhanced tooltip design with task information
- Renamed "Edit Time" to "Edit Focus time" for clarity

### Fixed
- Drag-and-drop task reordering not working
- Deprecated API usage

## [1.0.0 - 1.0.7] - 2025-12-27 to 2026-01-04

Initial development releases with core functionality:

- Hierarchical task management with up to 3 levels of subtasks
- Priority levels (High, Medium, Low)
- Focus timer with pause/resume controls
- Code location linking with gutter icons
- Drag-and-drop reordering with multi-select support
- Undo/Redo (25-step history)
- Show/Hide completed tasks
- Daily stats bar with task counters and focus time
- Keyboard shortcuts for task management