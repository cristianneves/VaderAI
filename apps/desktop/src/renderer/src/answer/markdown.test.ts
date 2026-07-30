import { describe, expect, it } from 'vitest';
import { isCodeBlock, languageOf } from './markdown';

describe('isCodeBlock', () => {
  it('treats a tagged fence as a block', () => {
    expect(isCodeBlock('language-python', 'def f():\n    pass')).toBe(true);
  });

  it('treats an untagged multi-line fence as a block', () => {
    expect(isCodeBlock(undefined, 'line one\nline two')).toBe(true);
  });

  it('treats a classless single line as inline', () => {
    expect(isCodeBlock(undefined, 'O(n log n)')).toBe(false);
  });

  it('keeps a one-line tagged fence a block', () => {
    // ```py\nprint(1)\n``` is still a fence, however short.
    expect(isCodeBlock('language-py', 'print(1)')).toBe(true);
  });

  it('treats empty inline code as inline', () => {
    expect(isCodeBlock(undefined, '')).toBe(false);
  });
});

describe('languageOf', () => {
  it('reads the tag off the class', () => {
    expect(languageOf('language-typescript')).toBe('typescript');
  });

  it('handles a tag with punctuation', () => {
    expect(languageOf('language-c++')).toBe('c++');
    expect(languageOf('language-objective-c')).toBe('objective-c');
  });

  it('is undefined for inline code', () => {
    expect(languageOf(undefined)).toBeUndefined();
  });

  it('is undefined for a class that carries no language', () => {
    expect(languageOf('something-else')).toBeUndefined();
  });
});
