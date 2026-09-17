/**
 * Pure diff-chunking helpers: split a large diff into coherent parts that each fit
 * within a character budget, so each part can be sent in its own AI request.
 *
 * Splitting is hierarchical: per file first (`diff --git` boundaries), then per hunk
 * (`@@` boundaries, repeating the file header so every part stays self-describing),
 * and only as a last resort per line.
 */

export function splitIntoFileBlocks(outputs: readonly string[]): string[] {
    const blocks: string[] = [];
    for (const output of outputs) {
        if (!output.trim()) {
            continue;
        }
        let current: string[] = [];
        for (const line of output.split('\n')) {
            if (line.startsWith('diff --git ') && current.length > 0) {
                blocks.push(current.join('\n'));
                current = [];
            }
            current.push(line);
        }
        if (current.length > 0) {
            blocks.push(current.join('\n'));
        }
    }
    return blocks.filter((block) => block.trim().length > 0);
}

function hardSplitLines(text: string, maxChars: number): string[] {
    const chunks: string[] = [];
    let current: string[] = [];
    let length = 0;
    for (const line of text.split('\n')) {
        if (current.length > 0 && length + line.length + 1 > maxChars) {
            chunks.push(current.join('\n'));
            current = [];
            length = 0;
        }
        current.push(line);
        length += line.length + 1;
    }
    if (current.length > 0) {
        chunks.push(current.join('\n'));
    }
    return chunks;
}

function splitOversizedBlock(block: string, maxChars: number): string[] {
    const lines = block.split('\n');
    const headerLines: string[] = [];
    let index = 0;
    while (index < lines.length && !lines[index].startsWith('@@ ')) {
        headerLines.push(lines[index]);
        index++;
    }
    // No hunk (binary file, mode change…) — only line-level splitting is possible.
    if (index >= lines.length) {
        return hardSplitLines(block, maxChars);
    }
    const header = headerLines.join('\n');
    const pieces: string[] = [];
    let current: string[] = [];
    let length = header.length;
    for (; index < lines.length; index++) {
        const line = lines[index];
        if (line.startsWith('@@ ') && current.length > 0 && length + line.length + 1 > maxChars) {
            pieces.push(`${header}\n${current.join('\n')}`);
            current = [];
            length = header.length;
        }
        current.push(line);
        length += line.length + 1;
    }
    if (current.length > 0) {
        pieces.push(`${header}\n${current.join('\n')}`);
    }
    return pieces.flatMap((piece) => (piece.length <= maxChars ? [piece] : hardSplitLines(piece, maxChars)));
}

export function buildChunks(blocks: readonly string[], maxChars: number): string[] {
    const pieces: string[] = [];
    for (const block of blocks) {
        if (block.length <= maxChars) {
            pieces.push(block);
        } else {
            pieces.push(...splitOversizedBlock(block, maxChars));
        }
    }
    const chunks: string[] = [];
    let current = '';
    for (const piece of pieces) {
        if (current === '') {
            current = piece;
            continue;
        }
        if (current.length + piece.length + 1 <= maxChars) {
            current += `\n${piece}`;
        } else {
            chunks.push(current);
            current = piece;
        }
    }
    if (current !== '') {
        chunks.push(current);
    }
    return chunks;
}
