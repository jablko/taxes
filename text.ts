import { toMdast, all, Options } from "hast-util-to-mdast";
import { handlers } from "hast-util-to-mdast/lib/handlers/index.js";
import { toText } from "hast-util-to-text";
import { u } from "unist-builder";
import remarkStringify from "remark-stringify";
import remarkFootnotes from "remark-footnotes";
import * as unist from "unist";
import * as hast from "hast";
import * as mdast from "mdast";
import { VFile } from "vfile";
import { Plugin, Transformer } from "unified";

type Narrowed<T, U> = (T extends unknown
  ? [U] extends [Partial<T>]
    ? T
    : never
  : never) &
  U;

declare module "hast" {
  interface Properties {
    id?: string;
    className?: string[];
    href?: string;
  }
}

const options: Options = {
  handlers: {
    a: (h, node: hast.Element) =>
      !node.properties?.className?.includes("fn-lnk")
        ? handlers.a(h, node)
        : h(node, "footnoteReference", {
            identifier: node.properties?.href?.replace(/^#fn/, ""),
          }),
    aside: (h, node: hast.Element) =>
      !node.properties?.className?.includes("wb-fnote")
        ? handlers.aside(h, node)
        : node.children
            .find(
              (dl): dl is Narrowed<typeof dl, { tagName: "dl" }> =>
                "tagName" in dl && dl.tagName === "dl"
            )
            ?.children.flatMap((dd) =>
              !("tagName" in dd) || dd.tagName !== "dd"
                ? []
                : h(
                    dd,
                    "footnoteDefinition",
                    { identifier: dd.properties?.id?.replace(/^fn/, "") },
                    all(h, dd)
                  )
            ),
  },
};

type Child<T> = T extends unist.Parent<infer U> ? U : never;
//type DescendantOrSelf<T> = T | DescendantOrSelf<Child<T>>;
type DescendantOrSelf<T> =
  | T
  | (T extends unist.Parent<infer U> ? DescendantOrSelf<Exclude<U, T>> : never);

function find<
  T extends unist.Node & Partial<unist.Parent>,
  U extends DescendantOrSelf<T>
>(node: T, predicate: (node: DescendantOrSelf<T>) => node is U): U | undefined {
  if (predicate(node)) return node;
  for (const child of node.children ?? []) {
    const found = find(child as never, predicate);
    if (found !== undefined) return found;
  }
  return undefined;
}

function findIndex<
  T extends unist.Node & Partial<unist.Parent>,
  U extends DescendantOrSelf<Child<T>>
>(
  node: T,
  predicate: (node: DescendantOrSelf<Child<T>>) => node is U
): [number, (DescendantOrSelf<T> & unist.Parent<U>) | undefined] {
  for (const [i, child] of node.children?.entries() ?? []) {
    if (predicate(child as Child<T>))
      return [i, node as typeof node & unist.Parent<U>];
    const [j, parent] = findIndex(child as never, predicate);
    if (j !== -1) return [j, parent];
  }
  return [-1, undefined];
}

function normalize(
  node: unist.Node & Partial<unist.Parent> & Partial<unist.Literal<string>>
) {
  node.value = node.value?.replace(/\s+/g, " ");
  for (const child of node.children ?? []) {
    normalize(child);
  }
}

function findLastIndex<T>(array: T[], predicate: (element: T) => boolean) {
  for (let i = array.length - 1; i >= 0; i--) {
    if (predicate(array[i]!)) return i;
  }
  return -1;
}

function visit(
  section: DescendantOrSelf<Child<hast.Element>>
): (mdast.Content | mdast.Root)[] {
  if (
    section.type !== "element" ||
    section.properties?.className?.includes("pagedetails")
  )
    return [];
  if (
    section.tagName === "div" &&
    !section.properties?.className?.includes("section")
  )
    return section.children.flatMap((child) => visit(child));
  const [i, parent] = findIndex(
    section,
    (remove): remove is typeof remove =>
      "properties" in remove &&
      ["fn-rtn", "wb-inv"].some((it) =>
        remove.properties?.className?.includes(it)
      )
  );
  if (i !== -1) parent!.children.splice(i, 1);
  normalize(section);
  const row = section.children.find(
    (row): row is Narrowed<typeof row, { type: "element" }> =>
      row.type === "element"
  );
  if (row === undefined) return [toMdast(section, options)];
  const j = findLastIndex(row.children, (last) => last.type === "element");
  if (j === -1) return [toMdast(section, options)];
  const text = ` ${toText(row.children[j]!)} `
    .replace(/\s+/g, " ")
    .slice(1, -1);
  const match = text.match(/^(?<line>[0-9]+) ?(?:- )?(?<amount>\$[^ ]*)/);
  if (match === null) return [toMdast(section, options)];
  row.children.splice(j, 1);
  const { line, amount } = match.groups!;
  return [
    u("paragraph", [
      u(
        "text",
        `Line ${line}: ${`${toText(row)} `.replace(/\s+/g, " ").slice(0, -1)} ${
          amount === "$" ? "^" : amount
        }`
      ),
    ]),
  ];
}

function transformer(tree: hast.Root, file: VFile) {
  file.extname = ".md";
  return u(
    "root",
    find(
      tree,
      (main): main is Narrowed<typeof main, { tagName: "main" }> =>
        "tagName" in main && main.tagName === "main"
    )!.children.flatMap((section) => visit(section))
  );
}

// https://github.com/microsoft/TypeScript/issues/16918
const attacher: Plugin = function () {
  this.use(remarkStringify).use(remarkFootnotes);
  return transformer as unknown as Transformer;
};
export default attacher;
