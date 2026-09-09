// UniFFI scaffolding emits a large metadata const array.
#![allow(clippy::large_const_arrays)]

use std::sync::Mutex;

use yrs::types::xml::XmlIn;
use yrs::{
    doc::{OffsetKind, Options},
    encoding::read::Cursor,
    sync::{
        protocol::{Message, SyncMessage},
        MessageReader,
    },
    updates::decoder::{Decode, DecoderV1},
    updates::encoder::{Encode, Encoder, EncoderV1},
    GetString, ReadTxn, Text, Transact, Update, WriteTxn, Xml, XmlElementPrelim, XmlFragment,
    XmlOut, XmlTextPrelim,
};

pub trait SyncDocObserver: Send + Sync {
    fn changed(&self);
}

uniffi::include_scaffolding!("syncbook");

pub enum BlockKind {
    Paragraph,
    TaskItem,
}

pub struct Block {
    pub id: String,
    pub kind: BlockKind,
    pub text: String,
    pub checked: bool,
}

#[derive(Clone)]
struct ParsedBlock {
    task: bool,
    checked: bool,
    text: String,
}

#[derive(Clone)]
struct FlatBlock {
    element: yrs::XmlElementRef,
    task: bool,
    checked: bool,
    text: String,
}

pub struct SyncDoc {
    doc: Mutex<yrs::Doc>,
    observers: Mutex<Vec<Box<dyn SyncDocObserver>>>,
}

impl SyncDoc {
    pub fn new() -> Self {
        Self {
            doc: Mutex::new(yrs::Doc::with_options(Options {
                offset_kind: OffsetKind::Utf16,
                ..Options::default()
            })),
            observers: Mutex::new(Vec::new()),
        }
    }

    pub fn apply_update(&self, update: Vec<u8>) {
        let Ok(update) = Update::decode_v1(&update) else {
            return;
        };
        let Ok(()) = self.doc.lock().unwrap().transact_mut().apply_update(update) else {
            return;
        };
        self.notify_observers();
    }

    pub fn state_vector(&self) -> Vec<u8> {
        self.doc
            .lock()
            .unwrap()
            .transact()
            .state_vector()
            .encode_v1()
    }

    pub fn sync_step1(&self) -> Vec<u8> {
        let vector = self.doc.lock().unwrap().transact().state_vector();
        let mut encoder = EncoderV1::new();
        Message::Sync(SyncMessage::SyncStep1(vector)).encode(&mut encoder);
        encoder.to_vec()
    }

    pub fn encode_update_message(&self, update: Vec<u8>) -> Vec<u8> {
        let mut encoder = EncoderV1::new();
        Message::Sync(SyncMessage::Update(update)).encode(&mut encoder);
        encoder.to_vec()
    }

    pub fn encode_state_as_update(&self, state_vector: Option<Vec<u8>>) -> Vec<u8> {
        let doc = self.doc.lock().unwrap();
        let vector = state_vector
            .and_then(|bytes| yrs::StateVector::decode_v1(&bytes).ok())
            .unwrap_or_default();
        let update = doc.transact().encode_state_as_update_v1(&vector);
        update
    }

    pub fn handle_message(&self, message: Vec<u8>) -> Vec<Vec<u8>> {
        let mut decoder = DecoderV1::new(Cursor::new(&message));
        let mut reader = MessageReader::new(&mut decoder);
        let doc = self.doc.lock().unwrap();
        let mut replies = Vec::new();
        let mut changed = false;

        while let Some(Ok(incoming)) = reader.next() {
            let response = match incoming {
                Message::Sync(SyncMessage::SyncStep1(vector)) => {
                    let update = doc.transact().encode_state_as_update_v1(&vector);
                    Some(Message::Sync(SyncMessage::SyncStep2(update)))
                }
                Message::Sync(SyncMessage::SyncStep2(update))
                | Message::Sync(SyncMessage::Update(update)) => {
                    if let Ok(update) = Update::decode_v1(&update) {
                        if doc.transact_mut().apply_update(update).is_ok() {
                            changed = true;
                        }
                    }
                    None
                }
                Message::Awareness(_) | Message::AwarenessQuery => None,
                _ => None,
            };
            if let Some(response) = response {
                let mut encoder = EncoderV1::new();
                response.encode(&mut encoder);
                replies.push(encoder.to_vec());
            }
        }

        drop(doc);
        if changed {
            self.notify_observers();
        }
        replies
    }

    pub fn blocks(&self) -> Vec<Block> {
        let doc = self.doc.lock().unwrap();
        let root = doc.get_or_insert_xml_fragment("prosemirror");
        let txn = doc.transact();
        top_level_blocks(&root, &txn)
    }

    pub fn markdown(&self) -> String {
        let doc = self.doc.lock().unwrap();
        let root = doc.get_or_insert_xml_fragment("prosemirror");
        let txn = doc.transact();
        flat_block_refs(&root, &txn)
            .into_iter()
            .map(|block| {
                if block.task {
                    format!(
                        "- [{}] {}",
                        if block.checked { "x" } else { " " },
                        block.text
                    )
                } else {
                    block.text
                }
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    pub fn set_markdown(&self, text: String) {
        let parsed = parse_markdown(&text);
        {
            let doc = self.doc.lock().unwrap();
            let mut txn = doc.transact_mut();
            let root = txn.get_or_insert_xml_fragment("prosemirror");
            let old = flat_block_refs(&root, &txn);
            let prefix = common_prefix(&old, &parsed);
            let suffix = common_suffix(&old, &parsed, prefix);
            let old_middle_end = old.len().saturating_sub(suffix);
            let new_middle_end = parsed.len().saturating_sub(suffix);
            let old_middle = &old[prefix..old_middle_end];
            let new_middle = &parsed[prefix..new_middle_end];

            if old_middle.len() == new_middle.len()
                && old_middle
                    .iter()
                    .zip(new_middle)
                    .all(|(old, new)| old.task == new.task)
            {
                for (old, new) in old_middle.iter().zip(new_middle) {
                    update_block(&mut txn, old, new);
                }
            } else {
                for old in old_middle.iter().rev() {
                    remove_flat_block(&mut txn, &root, &old.element);
                }
                for (offset, new) in new_middle.iter().enumerate() {
                    insert_flat_block(&mut txn, &root, prefix + offset, new);
                }
            }
        }
        self.notify_observers();
    }

    pub fn insert_text(&self, block_id: String, offset: u32, text: String) {
        if block_id.is_empty() && self.blocks().is_empty() {
            let doc = self.doc.lock().unwrap();
            let mut txn = doc.transact_mut();
            let root = txn.get_or_insert_xml_fragment("prosemirror");
            let element = root.push_back(&mut txn, XmlElementPrelim::empty("paragraph"));
            element.push_back(&mut txn, XmlTextPrelim::new(""));
        }
        let target_id = if block_id.is_empty() {
            self.blocks().first().map(|block| block.id.clone())
        } else {
            Some(block_id)
        };
        if let Some(target_id) = target_id {
            self.with_text(&target_id, |txn, node| node.insert(txn, offset, &text));
        }
        self.notify_observers();
    }

    pub fn delete_text(&self, block_id: String, offset: u32, length: u32) {
        self.with_text(&block_id, |txn, node| {
            node.remove_range(txn, offset, length)
        });
        self.notify_observers();
    }

    pub fn set_checked(&self, block_id: String, checked: bool) {
        {
            let doc = self.doc.lock().unwrap();
            let txn = &mut doc.transact_mut();
            if let Some(item) = find_element(txn, &block_id) {
                item.insert_attribute(txn, "checked", checked.to_string());
            }
        }
        self.notify_observers();
    }

    pub fn split_block(&self, block_id: String, offset: u32) {
        {
            let doc = self.doc.lock().unwrap();
            let txn = &mut doc.transact_mut();
            let Some(current) = find_element(txn, &block_id) else {
                return;
            };
            let text = element_text(&current, txn);
            let (left, right) = split_utf16(&text, offset);
            current.remove_range(txn, 0, current.len(txn));
            append_block_text(&current, txn, left);
            if let Some(parent) = current.parent() {
                let tag = current.tag().to_string();
                match parent {
                    XmlOut::Fragment(parent) => {
                        let index = fragment_element_index(&parent, txn, &block_id);
                        let inserted = parent.insert(txn, index + 1, XmlElementPrelim::empty(tag));
                        if current.tag().as_ref() == "taskItem" {
                            let checked = current
                                .get_attribute(txn, "checked")
                                .map(|value| value.to_string(txn))
                                .unwrap_or_else(|| "false".to_string());
                            inserted.insert_attribute(txn, "checked", checked);
                        }
                        append_block_text(&inserted, txn, right);
                    }
                    XmlOut::Element(parent) => {
                        let index = element_element_index(&parent, txn, &block_id);
                        let inserted = parent.insert(txn, index + 1, XmlElementPrelim::empty(tag));
                        if current.tag().as_ref() == "taskItem" {
                            let checked = current
                                .get_attribute(txn, "checked")
                                .map(|value| value.to_string(txn))
                                .unwrap_or_else(|| "false".to_string());
                            inserted.insert_attribute(txn, "checked", checked);
                        }
                        append_block_text(&inserted, txn, right);
                    }
                    XmlOut::Text(_) => {}
                }
            }
        }
        self.notify_observers();
    }

    pub fn toggle_task_list(&self, block_id: String) {
        {
            let doc = self.doc.lock().unwrap();
            let txn = &mut doc.transact_mut();
            let Some(element) = find_element(txn, &block_id) else {
                return;
            };
            if element.tag().as_ref() == "paragraph" {
                let text = element_text(&element, txn);
                let Some(parent) = element.parent().and_then(XmlOut::into_xml_fragment) else {
                    return;
                };
                let index = parent
                    .successors(txn)
                    .position(|node| matches!(&node, XmlOut::Element(candidate) if element_id(candidate) == block_id))
                    .map(|index| index as u32)
                    .unwrap_or(parent.len(txn));
                parent.remove_range(txn, index, 1);
                let list = parent.insert(txn, index, XmlElementPrelim::empty("taskList"));
                let item = list.push_back(txn, XmlElementPrelim::empty("taskItem"));
                item.insert_attribute(txn, "checked", "false");
                let paragraph = item.push_back(txn, XmlElementPrelim::empty("paragraph"));
                paragraph.push_back(txn, XmlTextPrelim::new(text));
            } else if element.tag().as_ref() == "taskItem" {
                let text = element_text(&element, txn);
                let Some(list) = element.parent().and_then(XmlOut::into_xml_element) else {
                    return;
                };
                let Some(parent) = list.parent().and_then(XmlOut::into_xml_fragment) else {
                    return;
                };
                let index = fragment_element_index(&parent, txn, &element_id(&list));
                parent.remove_range(txn, index, 1);
                let paragraph = parent.insert(txn, index, XmlElementPrelim::empty("paragraph"));
                paragraph.push_back(txn, XmlTextPrelim::new(text));
            }
        }
        self.notify_observers();
    }

    pub fn observe(&self, observer: Box<dyn SyncDocObserver>) {
        self.observers.lock().unwrap().push(observer);
    }

    pub fn clear_observers(&self) {
        self.observers.lock().unwrap().clear();
    }

    fn notify_observers(&self) {
        for observer in self.observers.lock().unwrap().iter() {
            observer.changed();
        }
    }

    fn with_text<F>(&self, block_id: &str, operation: F)
    where
        F: FnOnce(&mut yrs::TransactionMut<'_>, yrs::XmlTextRef),
    {
        let doc = self.doc.lock().unwrap();
        let txn = &mut doc.transact_mut();
        if let Some(element) = find_element(txn, block_id) {
            let text = block_text_ref(txn, &element);
            operation(txn, text);
        }
    }
}

impl Default for SyncDoc {
    fn default() -> Self {
        Self::new()
    }
}

fn find_element<'a>(txn: &'a yrs::TransactionMut<'a>, id: &str) -> Option<yrs::XmlElementRef> {
    let root = txn.get_xml_fragment("prosemirror")?;
    find_in_fragment(&root, txn, id)
}

fn find_in_fragment<T: yrs::ReadTxn>(
    fragment: &yrs::XmlFragmentRef,
    txn: &T,
    id: &str,
) -> Option<yrs::XmlElementRef> {
    for index in 0..fragment.len(txn) {
        if let Some(XmlOut::Element(element)) = fragment.get(txn, index) {
            if let Some(found) = find_in_element(&element, txn, id) {
                return Some(found);
            }
        }
    }
    None
}

fn find_in_element<T: yrs::ReadTxn>(
    element: &yrs::XmlElementRef,
    txn: &T,
    id: &str,
) -> Option<yrs::XmlElementRef> {
    if element_id(element) == id {
        return Some(element.clone());
    }
    for index in 0..element.len(txn) {
        if let Some(XmlOut::Element(child)) = element.get(txn, index) {
            if let Some(found) = find_in_element(&child, txn, id) {
                return Some(found);
            }
        }
    }
    None
}

fn top_level_blocks<T: yrs::ReadTxn>(root: &yrs::XmlFragmentRef, txn: &T) -> Vec<Block> {
    let mut blocks = Vec::new();
    for index in 0..root.len(txn) {
        let Some(XmlOut::Element(element)) = root.get(txn, index) else {
            continue;
        };
        match element.tag().as_ref() {
            "paragraph" => blocks.push(Block {
                id: element_id(&element),
                kind: BlockKind::Paragraph,
                text: element_text(&element, txn),
                checked: false,
            }),
            "taskList" => {
                for child_index in 0..element.len(txn) {
                    if let Some(XmlOut::Element(item)) = element.get(txn, child_index) {
                        if item.tag().as_ref() == "taskItem" {
                            blocks.push(task_block(&item, txn));
                        }
                    }
                }
            }
            _ => {}
        }
    }
    blocks
}

fn parse_markdown(text: &str) -> Vec<ParsedBlock> {
    if text.is_empty() {
        return Vec::new();
    }
    text.split('\n')
        .map(|line| {
            let (task, checked, content) = if let Some(content) = line.strip_prefix("- [ ] ") {
                (true, false, content)
            } else if let Some(content) = line.strip_prefix("- [x] ") {
                (true, true, content)
            } else if let Some(content) = line.strip_prefix("- [X] ") {
                (true, true, content)
            } else if line == "- [ ]" {
                (true, false, "")
            } else if matches!(line, "- [x]" | "- [X]") {
                (true, true, "")
            } else {
                (false, false, line)
            };
            ParsedBlock {
                task,
                checked,
                text: content.to_string(),
            }
        })
        .collect()
}

fn flat_block_refs<T: yrs::ReadTxn>(root: &yrs::XmlFragmentRef, txn: &T) -> Vec<FlatBlock> {
    let mut blocks = Vec::new();
    for index in 0..root.len(txn) {
        let Some(XmlOut::Element(element)) = root.get(txn, index) else {
            continue;
        };
        match element.tag().as_ref() {
            "paragraph" => blocks.push(FlatBlock {
                element: element.clone(),
                task: false,
                checked: false,
                text: element_text(&element, txn),
            }),
            "taskList" => {
                for child_index in 0..element.len(txn) {
                    let Some(XmlOut::Element(item)) = element.get(txn, child_index) else {
                        continue;
                    };
                    if item.tag().as_ref() == "taskItem" {
                        blocks.push(FlatBlock {
                            element: item.clone(),
                            task: true,
                            checked: item
                                .get_attribute(txn, "checked")
                                .map(|value| value.to_string(txn) == "true")
                                .unwrap_or(false),
                            text: element_text(&item, txn),
                        });
                    }
                }
            }
            _ => {}
        }
    }
    blocks
}

fn common_prefix(old: &[FlatBlock], new: &[ParsedBlock]) -> usize {
    old.iter()
        .zip(new)
        .take_while(|(old, new)| {
            old.task == new.task && old.checked == new.checked && old.text == new.text
        })
        .count()
}

fn common_suffix(old: &[FlatBlock], new: &[ParsedBlock], prefix: usize) -> usize {
    old.iter()
        .rev()
        .zip(new.iter().rev())
        .take_while(|(old, new)| {
            old.task == new.task && old.checked == new.checked && old.text == new.text
        })
        .take(old.len().saturating_sub(prefix))
        .take(new.len().saturating_sub(prefix))
        .count()
}

fn update_block(txn: &mut yrs::TransactionMut<'_>, old: &FlatBlock, new: &ParsedBlock) {
    if old.text != new.text {
        let text = block_text_ref(txn, &old.element);
        replace_text(txn, text, &old.text, &new.text);
    }
    if old.task && old.checked != new.checked {
        old.element
            .insert_attribute(txn, "checked", new.checked.to_string());
    }
}

fn replace_text(txn: &mut yrs::TransactionMut<'_>, text: yrs::XmlTextRef, old: &str, new: &str) {
    let old_units = old.encode_utf16().count() as u32;
    let new_units = new.encode_utf16().count() as u32;
    let old_chars: Vec<char> = old.chars().collect();
    let new_chars: Vec<char> = new.chars().collect();
    let mut prefix_chars = 0;
    while prefix_chars < old_chars.len()
        && prefix_chars < new_chars.len()
        && old_chars[prefix_chars] == new_chars[prefix_chars]
    {
        prefix_chars += 1;
    }
    let mut suffix_chars = 0;
    while suffix_chars < old_chars.len().saturating_sub(prefix_chars)
        && suffix_chars < new_chars.len().saturating_sub(prefix_chars)
        && old_chars[old_chars.len() - 1 - suffix_chars]
            == new_chars[new_chars.len() - 1 - suffix_chars]
    {
        suffix_chars += 1;
    }
    let prefix: u32 = old_chars[..prefix_chars]
        .iter()
        .map(|character| character.len_utf16() as u32)
        .sum();
    let suffix: u32 = old_chars[old_chars.len() - suffix_chars..]
        .iter()
        .map(|character| character.len_utf16() as u32)
        .sum();
    let old_middle = old_units.saturating_sub(prefix + suffix);
    if old_middle > 0 {
        text.remove_range(txn, prefix, old_middle);
    }
    let inserted = utf16_slice(new, prefix, new_units.saturating_sub(suffix));
    if !inserted.is_empty() {
        text.insert(txn, prefix, inserted);
    }
}

fn utf16_slice(text: &str, start: u32, end: u32) -> &str {
    fn byte_offset(text: &str, target: u32) -> usize {
        let mut units = 0;
        for (index, character) in text.char_indices() {
            if units == target {
                return index;
            }
            units += character.len_utf16() as u32;
        }
        text.len()
    }
    let start_byte = byte_offset(text, start);
    let end_byte = byte_offset(text, end);
    &text[start_byte..end_byte]
}

fn block_text_ref(
    txn: &mut yrs::TransactionMut<'_>,
    element: &yrs::XmlElementRef,
) -> yrs::XmlTextRef {
    if element.tag().as_ref() == "taskItem" {
        let paragraph = (0..element.len(txn)).find_map(|index| match element.get(txn, index) {
            Some(XmlOut::Element(child)) if child.tag().as_ref() == "paragraph" => Some(child),
            _ => None,
        });
        let paragraph = paragraph
            .unwrap_or_else(|| element.push_back(txn, XmlElementPrelim::empty("paragraph")));
        if let Some(XmlOut::Text(text)) = paragraph.get(txn, 0) {
            text
        } else {
            paragraph.push_back(txn, XmlTextPrelim::new(""))
        }
    } else if let Some(XmlOut::Text(text)) = element.get(txn, 0) {
        text
    } else {
        element.push_back(txn, XmlTextPrelim::new(""))
    }
}

fn remove_flat_block(
    txn: &mut yrs::TransactionMut<'_>,
    root: &yrs::XmlFragmentRef,
    element: &yrs::XmlElementRef,
) {
    match element.parent() {
        Some(XmlOut::Fragment(parent)) => {
            let index = fragment_element_index(&parent, txn, &element_id(element));
            parent.remove_range(txn, index, 1);
        }
        Some(XmlOut::Element(parent)) => {
            let index = element_element_index(&parent, txn, &element_id(element));
            parent.remove_range(txn, index, 1);
            if parent.len(txn) == 0 {
                let index = fragment_element_index(root, txn, &element_id(&parent));
                root.remove_range(txn, index, 1);
            }
        }
        Some(XmlOut::Text(_)) | None => {}
    }
}

fn insert_flat_block(
    txn: &mut yrs::TransactionMut<'_>,
    root: &yrs::XmlFragmentRef,
    position: usize,
    block: &ParsedBlock,
) {
    let current = flat_block_refs(root, txn);
    let previous = position.checked_sub(1).and_then(|index| current.get(index));
    let next = current.get(position);
    if block.task {
        if let Some(previous) = previous.filter(|block| block.task) {
            let Some(XmlOut::Element(list)) = previous.element.parent() else {
                return;
            };
            let index = element_element_index(&list, txn, &element_id(&previous.element));
            insert_task_item(txn, &list, index + 1, block);
        } else if let Some(next) = next.filter(|block| block.task) {
            let Some(XmlOut::Element(list)) = next.element.parent() else {
                return;
            };
            insert_task_item(txn, &list, 0, block);
        } else {
            let index = top_level_insert_index(root, txn, previous, next);
            let list = root.insert(txn, index, XmlElementPrelim::empty("taskList"));
            insert_task_item(txn, &list, 0, block);
        }
    } else if let (Some(previous), Some(next)) = (previous, next) {
        if previous.task && next.task {
            let Some(XmlOut::Element(previous_list)) = previous.element.parent() else {
                return;
            };
            let Some(XmlOut::Element(next_list)) = next.element.parent() else {
                return;
            };
            if previous_list == next_list {
                split_task_list(txn, root, &previous_list, &next.element);
                let index = fragment_element_index(root, txn, &element_id(&previous_list)) + 1;
                root.insert(txn, index, paragraph_prelim(block));
                return;
            }
        }
        let index = top_level_insert_index(root, txn, Some(previous), Some(next));
        root.insert(txn, index, paragraph_prelim(block));
    } else {
        let index = top_level_insert_index(root, txn, previous, next);
        root.insert(txn, index, paragraph_prelim(block));
    }
}

fn insert_task_item(
    txn: &mut yrs::TransactionMut<'_>,
    list: &yrs::XmlElementRef,
    index: u32,
    block: &ParsedBlock,
) {
    let item = list.insert(txn, index, XmlElementPrelim::empty("taskItem"));
    item.insert_attribute(txn, "checked", block.checked.to_string());
    let paragraph = item.push_back(txn, XmlElementPrelim::empty("paragraph"));
    paragraph.push_back(txn, XmlTextPrelim::new(block.text.clone()));
}

fn paragraph_prelim(block: &ParsedBlock) -> XmlElementPrelim {
    XmlElementPrelim::new(
        "paragraph",
        [XmlIn::Text(XmlTextPrelim::new(block.text.clone()).into())],
    )
}

fn top_level_insert_index<T: yrs::ReadTxn>(
    root: &yrs::XmlFragmentRef,
    txn: &T,
    previous: Option<&FlatBlock>,
    next: Option<&FlatBlock>,
) -> u32 {
    if let Some(previous) = previous {
        let top = top_level_element(previous, txn);
        return fragment_element_index(root, txn, &element_id(&top)) + 1;
    }
    if let Some(next) = next {
        let top = top_level_element(next, txn);
        return fragment_element_index(root, txn, &element_id(&top));
    }
    root.len(txn)
}

fn top_level_element<T: yrs::ReadTxn>(block: &FlatBlock, txn: &T) -> yrs::XmlElementRef {
    if !block.task {
        return block.element.clone();
    }
    let Some(XmlOut::Element(list)) = block.element.parent() else {
        return block.element.clone();
    };
    let _ = txn;
    list
}

fn split_task_list(
    txn: &mut yrs::TransactionMut<'_>,
    root: &yrs::XmlFragmentRef,
    list: &yrs::XmlElementRef,
    next: &yrs::XmlElementRef,
) {
    let next_index = element_element_index(list, txn, &element_id(next));
    let tail: Vec<ParsedBlock> = (next_index..list.len(txn))
        .filter_map(|index| match list.get(txn, index) {
            Some(XmlOut::Element(item)) if item.tag().as_ref() == "taskItem" => Some(ParsedBlock {
                task: true,
                checked: item
                    .get_attribute(txn, "checked")
                    .map(|value| value.to_string(txn) == "true")
                    .unwrap_or(false),
                text: element_text(&item, txn),
            }),
            _ => None,
        })
        .collect();
    list.remove_range(txn, next_index, list.len(txn) - next_index);
    let list_index = fragment_element_index(root, txn, &element_id(list));
    let new_list = root.insert(txn, list_index + 1, XmlElementPrelim::empty("taskList"));
    for block in tail {
        insert_task_item(txn, &new_list, new_list.len(txn), &block);
    }
}

fn element_text<T: yrs::ReadTxn>(element: &yrs::XmlElementRef, txn: &T) -> String {
    (0..element.len(txn))
        .filter_map(|index| match element.get(txn, index) {
            Some(XmlOut::Text(text)) => Some(text.get_string(txn)),
            Some(XmlOut::Element(child)) => Some(element_text(&child, txn)),
            _ => None,
        })
        .collect()
}

fn fragment_element_index<T: yrs::ReadTxn>(parent: &yrs::XmlFragmentRef, txn: &T, id: &str) -> u32 {
    for index in 0..parent.len(txn) {
        if let Some(XmlOut::Element(candidate)) = parent.get(txn, index) {
            if element_id(&candidate) == id {
                return index;
            }
        }
    }
    parent.len(txn)
}

fn element_element_index<T: yrs::ReadTxn>(parent: &yrs::XmlElementRef, txn: &T, id: &str) -> u32 {
    for index in 0..parent.len(txn) {
        if let Some(XmlOut::Element(candidate)) = parent.get(txn, index) {
            if element_id(&candidate) == id {
                return index;
            }
        }
    }
    parent.len(txn)
}

fn append_block_text(element: &yrs::XmlElementRef, txn: &mut yrs::TransactionMut<'_>, text: &str) {
    if element.tag().as_ref() == "taskItem" {
        let paragraph = element.push_back(txn, XmlElementPrelim::empty("paragraph"));
        paragraph.push_back(txn, XmlTextPrelim::new(text));
    } else {
        element.push_back(txn, XmlTextPrelim::new(text));
    }
}

fn split_utf16(text: &str, offset: u32) -> (&str, &str) {
    let mut units = 0;
    for (index, character) in text.char_indices() {
        if units >= offset {
            return text.split_at(index);
        }
        units += character.len_utf16() as u32;
    }
    (text, "")
}

fn task_block<T: yrs::ReadTxn>(item: &yrs::XmlElementRef, txn: &T) -> Block {
    Block {
        id: element_id(item),
        kind: BlockKind::TaskItem,
        text: element_text(item, txn),
        checked: item
            .get_attribute(txn, "checked")
            .map(|value| value.to_string(txn) == "true")
            .unwrap_or(false),
    }
}

fn element_id(element: &yrs::XmlElementRef) -> String {
    format!("{:?}", yrs::XmlOut::Element(element.clone()).id())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sync_message(message: Message) -> Vec<u8> {
        let mut encoder = EncoderV1::new();
        message.encode(&mut encoder);
        encoder.to_vec()
    }

    fn sync_pair(left: &SyncDoc, right: &SyncDoc) {
        let left_step = sync_message(Message::Sync(SyncMessage::SyncStep1(
            yrs::StateVector::decode_v1(&left.state_vector()).unwrap(),
        )));
        for reply in right.handle_message(left_step) {
            left.handle_message(reply);
        }
        let right_step = sync_message(Message::Sync(SyncMessage::SyncStep1(
            yrs::StateVector::decode_v1(&right.state_vector()).unwrap(),
        )));
        for reply in left.handle_message(right_step) {
            right.handle_message(reply);
        }
    }

    #[test]
    fn docs_converge_through_sync_messages() {
        let left = SyncDoc::new();
        let right = SyncDoc::new();
        left.insert_text(String::new(), 0, String::new());
        let id = left.blocks()[0].id.clone();
        left.insert_text(id, 0, "hello".to_string());

        sync_pair(&left, &right);

        assert_eq!(right.blocks()[0].text, "hello");
    }

    #[test]
    fn concurrent_same_offset_inserts_both_survive() {
        let left = SyncDoc::new();
        let right = SyncDoc::new();
        left.insert_text(String::new(), 0, String::new());
        sync_pair(&left, &right);
        let left_id = left.blocks()[0].id.clone();
        let right_id = right.blocks()[0].id.clone();
        left.insert_text(left_id, 0, "left".to_string());
        right.insert_text(right_id, 0, "right".to_string());

        sync_pair(&left, &right);

        let text = &left.blocks()[0].text;
        assert!(text.contains("left"));
        assert!(text.contains("right"));
        assert_eq!(text, &right.blocks()[0].text);
    }

    #[test]
    fn concurrent_utf16_same_offset_inserts_both_survive() {
        let left = SyncDoc::new();
        let right = SyncDoc::new();
        left.insert_text(String::new(), 0, "ä😀".to_string());
        sync_pair(&left, &right);
        let left_id = left.blocks()[0].id.clone();
        let right_id = right.blocks()[0].id.clone();
        left.insert_text(left_id, 1, "left".to_string());
        right.insert_text(right_id, 1, "right".to_string());

        sync_pair(&left, &right);

        let text = &left.blocks()[0].text;
        assert!(text.contains("left"));
        assert!(text.contains("right"));
        assert_eq!(text, &right.blocks()[0].text);
    }

    #[test]
    fn checked_state_round_trips() {
        let doc = SyncDoc::new();
        doc.insert_text(String::new(), 0, String::new());
        let paragraph_id = doc.blocks()[0].id.clone();
        doc.toggle_task_list(paragraph_id);
        let task_id = doc.blocks()[0].id.clone();
        doc.set_checked(task_id, true);

        let block = &doc.blocks()[0];
        assert!(matches!(block.kind, BlockKind::TaskItem));
        assert!(block.checked);
    }

    #[test]
    fn snapshot_loads_into_fresh_document() {
        let original = SyncDoc::new();
        original.insert_text(String::new(), 0, String::new());
        let id = original.blocks()[0].id.clone();
        original.insert_text(id, 0, "snapshot".to_string());
        let snapshot = original.encode_state_as_update(None);

        let restored = SyncDoc::new();
        restored.apply_update(snapshot);

        assert_eq!(restored.blocks()[0].text, "snapshot");
    }

    #[test]
    fn markdown_round_trips_mixed_document() {
        let doc = SyncDoc::new();
        doc.set_markdown("intro\n- [ ] open\n- [x] done\noutro".to_string());

        assert_eq!(doc.markdown(), "intro\n- [ ] open\n- [x] done\noutro");
        let blocks = doc.blocks();
        assert_eq!(blocks.len(), 4);
        assert!(matches!(blocks[0].kind, BlockKind::Paragraph));
        assert!(matches!(blocks[1].kind, BlockKind::TaskItem));
        assert!(matches!(blocks[2].kind, BlockKind::TaskItem));
        assert!(matches!(blocks[3].kind, BlockKind::Paragraph));
        assert!(!blocks[1].checked);
        assert!(blocks[2].checked);
    }

    #[test]
    fn markdown_line_edit_preserves_block_ids() {
        let doc = SyncDoc::new();
        doc.set_markdown("first\nsecond\nthird".to_string());
        let before = doc.blocks();

        doc.set_markdown("first\nseXond\nthird".to_string());

        let after = doc.blocks();
        assert_eq!(
            before.iter().map(|block| &block.id).collect::<Vec<_>>(),
            after.iter().map(|block| &block.id).collect::<Vec<_>>()
        );
        assert_eq!(after[0].text, "first");
        assert_eq!(after[1].text, "seXond");
        assert_eq!(after[2].text, "third");
    }

    #[test]
    fn markdown_toggle_wraps_paragraph_in_task_list() {
        let doc = SyncDoc::new();
        doc.set_markdown("one\noutside".to_string());
        doc.set_markdown("- [ ] one\noutside".to_string());

        assert!(matches!(doc.blocks()[0].kind, BlockKind::TaskItem));
        let binding = doc.doc.lock().unwrap();
        let txn = binding.transact();
        let root = txn.get_xml_fragment("prosemirror").unwrap();
        assert_eq!(root.len(&txn), 2);
        let XmlOut::Element(first) = root.get(&txn, 0).unwrap() else {
            panic!("expected task list");
        };
        assert_eq!(first.tag().as_ref(), "taskList");
    }

    #[test]
    fn markdown_paragraph_after_task_is_outside_list() {
        let doc = SyncDoc::new();
        doc.set_markdown("- [ ] task\nparagraph".to_string());

        let binding = doc.doc.lock().unwrap();
        let txn = binding.transact();
        let root = txn.get_xml_fragment("prosemirror").unwrap();
        assert_eq!(root.len(&txn), 2);
        let XmlOut::Element(first) = root.get(&txn, 0).unwrap() else {
            panic!("expected task list");
        };
        let XmlOut::Element(second) = root.get(&txn, 1).unwrap() else {
            panic!("expected paragraph");
        };
        assert_eq!(first.tag().as_ref(), "taskList");
        assert_eq!(second.tag().as_ref(), "paragraph");
    }

    #[test]
    fn markdown_task_after_task_joins_list() {
        let doc = SyncDoc::new();
        doc.set_markdown("- [ ] first\n- [ ] second".to_string());

        let binding = doc.doc.lock().unwrap();
        let txn = binding.transact();
        let root = txn.get_xml_fragment("prosemirror").unwrap();
        let XmlOut::Element(list) = root.get(&txn, 0).unwrap() else {
            panic!("expected task list");
        };
        assert_eq!(list.tag().as_ref(), "taskList");
        assert_eq!(list.len(&txn), 2);
    }

    #[test]
    fn concurrent_markdown_edits_converge() {
        let left = SyncDoc::new();
        let right = SyncDoc::new();
        left.set_markdown("left\nright".to_string());
        sync_pair(&left, &right);

        left.set_markdown("left edited\nright".to_string());
        right.set_markdown("left\nright edited".to_string());
        sync_pair(&left, &right);

        assert_eq!(left.markdown(), right.markdown());
        assert!(left.markdown().contains("left edited"));
        assert!(left.markdown().contains("right edited"));
    }

    #[test]
    fn empty_markdown_empties_document() {
        let doc = SyncDoc::new();
        doc.set_markdown("content\n- [ ] task".to_string());
        doc.set_markdown(String::new());

        assert!(doc.blocks().is_empty());
        assert_eq!(doc.markdown(), "");
    }
}
