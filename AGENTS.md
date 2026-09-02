# AGENTS.md

This file provides guidance to agents when working with code in this repository.

## Project Overview

**MCP2AI** — a Streamable SSE MCP (Model Context Protocol) server that proxies requests to a second (e.g. local) AI model for review/validation purposes.

## Current State

The repository is in a **pre-implementation state**. As of now it contains only:
- `README.md` — one-line description
- `.project` — Eclipse project descriptor (project name: `MCP2AI`)

No source code, build configuration, package manager files, or tests exist yet.

## Notes for When Code is Added

- The `.project` file signals an Eclipse-based Java project origin, but the final language/framework has not been committed to yet.
- Once source files are added, update this file with build/test commands, code style rules, and architectural notes discovered from config files and code.
- MCP (Model Context Protocol) server implementations typically use the `@modelcontextprotocol/sdk` (Node.js/TypeScript) or `mcp` (Python) library — confirm which is chosen when scaffolding begins.
