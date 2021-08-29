const cache = require('../cache');
const insure = require('./insure');
const select = require('./select');
const request = require('../request');

const headers = {
	origin: 'http://y.qq.com/',
	referer: 'http://y.qq.com/',
	cookie: process.env.QQ_COOKIE || null, // 'uin=; qm_keyst=',
};

var myguid = String(Math.round(2147483647 * Math.random()) * (new Date).getUTCMilliseconds() % 1e10)

var weight = 0

const format = (song) => ({
	id: {
		song: song.mid,
		file: song.file.media_mid
	},
	name: song.name,
	duration: song.interval * 1000,
	album: {
		id: song.album.mid,
		name: song.album.name
	},
	artists: song.singer.map(({
		mid,
		name
	}) => ({
		id: mid,
		name
	})),
	weight: 0
});

const search = (info) => {
	const url =
		'https://c.y.qq.com/soso/fcgi-bin/client_search_cp?' +
		'ct=24&qqmusic_ver=1298&new_json=1&remoteplace=txt.yqq.center&' +
		't=0&aggr=1&cr=1&catZhida=1&lossless=0&flag_qc=0&p=1&n=20&w=' +
		encodeURIComponent(info.keyword) +
		'&' +
		'g_tk=5381&jsonpCallback=MusicJsonCallback10005317669353331&loginUin=0&hostUin=0&' +
		'format=jsonp&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0';

	return request('GET', url)
		.then((response) => response.jsonp())
		.then((jsonBody) => {
			const list = jsonBody.data.song.list.map(format);
			const matched = select.selectList(list, info);
			weight = matched.weight
			return matched ? matched.id : Promise.reject();
		});
};

const single = (id, format) => {
	const uin = ((headers.cookie || '').match(/uin=(\d+)/) || [])[1] || '0';

	const url =
		'https://u.y.qq.com/cgi-bin/musicu.fcg?data=' +
		encodeURIComponent(
			JSON.stringify({
				req_0: {
					module: 'vkey.GetVkeyServer',
					method: 'CgiGetVkey',
					param: {
						guid: myguid,
						loginflag: 1,
						filename: [format.join(id.file)],
						songmid: [id.song],
						songtype: [0],
						uin,
						platform: '20',
					},
				},
			})
		);

	return request('GET', url, headers)
		.then((response) => response.json())
		.then((jsonBody) => {
			const {
				sip,
				midurlinfo
			} = jsonBody.req_0.data;
			return midurlinfo[0].purl ? {
					url: sip[0] + midurlinfo[0].purl,
					weight: weight
				} :
				Promise.reject();
		});
};

const track = (id) => {
	id.key = id.file;
	return Promise.all(
			[
				['F000', '.flac'],
				['M800', '.mp3'],
				['M500', '.mp3'],
			]
			.slice(
				headers.cookie || typeof window !== 'undefined' ?
				select.ENABLE_FLAC ?
				0 :
				1 :
				2
			)
			.map((format) => single(id, format).catch(() => null))
		)
		.then((result) => result.find((url) => url) || Promise.reject())
		.catch(() => insure().qq.track(id));
};

const check = (info) => cache(search, info).then(track);

module.exports = {
	check,
	track
};
