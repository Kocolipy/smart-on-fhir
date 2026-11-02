import { clsx, type ClassValue } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

// DESIGN.md's named type scale, which src/index.css defines as `--text-<level>`
// tokens. twMerge knows only Tailwind's stock font sizes, and reads any other
// `text-*` as a colour — so without this, a caller's `text-muted-foreground`
// would silently drop a component's `text-headline-lg`. A level added to
// index.css's `@theme inline` block is added to this list too; utils.test.ts
// reads the levels out of index.css and fails on one missing here.
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      text: [
        "body-sm",
        "body-md",
        "body-lg",
        "label-md",
        "data-mono",
        "headline-sm",
        "headline-md",
        "headline-lg",
      ],
    },
  },
});

/**
 * Joins class names and resolves Tailwind conflicts, last one winning.
 *
 * Both halves matter: clsx flattens the conditional forms (arrays, objects,
 * falsy values) and twMerge then drops the earlier of any two utilities that
 * set the same property — so a caller's `px-8` overrides a component's default
 * `px-4` instead of landing in the same class list and losing to CSS source
 * order.
 *
 * This is the `cn` the shadcn generator emits, and the in-house component
 * library expects to find it at this path.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
