#!/usr/bin/env python3
"""Select native parents or reconstruct them from four complete child images."""
import argparse
import os
from pathlib import Path
import shutil
from PIL import Image


def tile_path(root, key):
    return root.joinpath(*key[1:], key + '.png')


def build(source, target, destination, level):
    if not source.is_dir() or target.exists() or level < 0:
        raise ValueError('Invalid source, target or level')
    candidates = set()
    available = {}
    for path in source.rglob('*.png'):
        key = path.stem
        if key.startswith('0') and set(key) <= set('0123'):
            available[key] = path
            if len(key) > level:
                candidates.add(key[:level + 1])
    # Work bottom-up; a parent is valid only with all four quadrants.
    prefixes = {key[:length] for key in available for length in range(1, len(key))}
    generated = {}
    def image_for(key):
        if key in available:
            with Image.open(available[key]) as image:
                return image.convert('RGBA')
        if key in generated:
            return generated[key]
        if key not in prefixes:
            return None
        children = [image_for(key + digit) for digit in '0123']
        if any(image is None for image in children):
            return None
        image = Image.new('RGBA', (256, 256))
        for child, position in zip(children, [(0, 128), (128, 128), (128, 0), (0, 0)]):
            image.paste(child.resize((128, 128), Image.Resampling.LANCZOS), position)
        generated[key] = image
        return image
    selected = {}
    for key in sorted(candidates):
        image = image_for(key)
        if image is not None:
            selected[key] = image
    if not selected:
        raise ValueError(f'Full delta contains no complete level-{level} tiles')
    ancestors = {key[:length] for key in selected for length in range(1, level + 1)}
    ancestor_sources = {}
    ancestor_images = {}
    for key in ancestors:
        existing = tile_path(destination, key)
        if existing.is_file():
            ancestor_sources[key] = existing
        elif key in available:
            # A new geographic region may lack parent tiles in the destination.
            # Publish its native exported ancestors while preserving existing ones.
            with Image.open(available[key]) as image:
                if image.size != (256, 256):
                    raise ValueError(f'Exported ancestor {key} is not native 256x256')
            ancestor_sources[key] = available[key]
        else:
            image = image_for(key)
            if image is None:
                raise ValueError(f'Destination and complete export lack ancestor {key}')
            ancestor_images[key] = image
    target.mkdir()
    for key, image in selected.items():
        path = tile_path(target, key)
        path.parent.mkdir(parents=True, exist_ok=True)
        if key in available:
            os.link(available[key], path)
        else:
            image.save(path)
    for key in ancestors:
        path = tile_path(target, key)
        path.parent.mkdir(parents=True, exist_ok=True)
        if key in ancestor_sources:
            shutil.copyfile(ancestor_sources[key], path)
        else:
            ancestor_images[key].save(path)
    exported_ancestors = sum(not tile_path(destination, key).is_file() for key in ancestors)
    print(f'Prepared level-{level}-only delta: tiles={len(selected)} reconstructed={sum(k not in available for k in selected)} destination_ancestors={len(ancestors)-exported_ancestors} exported_ancestors={exported_ancestors} reconstructed_ancestors={len(ancestor_images)} path={target}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('target', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('level', type=int)
    args = parser.parse_args()
    build(args.source, args.target, args.destination, args.level)
